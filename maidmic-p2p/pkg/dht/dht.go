// maidmic-p2p/pkg/dht/dht.go
// MaidMic P2P DHT 网络
// ============================================================
// 基于 Kademlia 协议的分布式哈希表，用于插件发现和 P2P 节点寻址。
//
// 在 MaidMic 生态中，DHT 负责两件事：
// 1. 找到拥有某个插件分片的其他节点
// 2. 交换节点列表（发现更多下载源）
//
// Kademlia 的优点：
// - 去中心化，不需要中央 tracker 服务器
// - 节点失效自动修复（周期性刷新 buckets）
// - 适合移动端（低带宽开销）

package dht

import (
	"container/list"
	"crypto/rand"
	"crypto/sha1"
	"encoding/hex"
	"fmt"
	"math/big"
	"net"
	"sort"
	"sync"
	"time"
)

// ============================================================
// 基础类型
// Basic types
// ============================================================

// NodeID 是 160 位 SHA1 哈希值，作为节点在 DHT 中的唯一标识
// NodeID is a 160-bit SHA1 hash, unique identifier in the DHT network
type NodeID [20]byte

func (id NodeID) String() string {
	return hex.EncodeToString(id[:])
}

// 生成随机 NodeID
func GenerateNodeID() NodeID {
	var id NodeID
	rand.Read(id[:])
	return id
}

// 从字符串解析 NodeID
func NodeIDFromString(s string) (NodeID, error) {
	var id NodeID
	decoded, err := hex.DecodeString(s)
	if err != nil {
		return id, err
	}
	copy(id[:], decoded)
	return id, nil
}

// ============================================================
// 节点信息
// Node info
// ============================================================
// 代表 DHT 网络中的一个节点

type NodeInfo struct {
	ID        NodeID    // 节点 ID
	IP        net.IP    // IP 地址
	Port      int       // 端口
	LastSeen  time.Time // 最后活跃时间
	IsMaidMic bool      // 是否是 MaidMic 节点（vs 普通 BitTorrent DHT 节点）
}

// ============================================================
// KBucket — Kademlia 桶
// ============================================================
// 每个 bucket 最多保存 K 个节点。
// 当 bucket 满了，移除最久未活跃的节点。
// K 值 = 8（标准 Kademlia K 值），移动端保持轻量。

const K = 8

type KBucket struct {
	sync.RWMutex
	nodes    *list.List // 存 *NodeInfo，按活跃时间排序
	modified time.Time  // 最后修改时间
}

func NewKBucket() *KBucket {
	return &KBucket{
		nodes: list.New(),
	}
}

// 向 bucket 插入或更新节点
// Insert or update a node in the bucket
func (b *KBucket) Upsert(node *NodeInfo) {
	b.Lock()
	defer b.Unlock()

	// 遍历查找是否已存在
	for e := b.nodes.Front(); e != nil; e = e.Next() {
		existing := e.Value.(*NodeInfo)
		if existing.ID == node.ID {
			// 节点已存在，移到尾部（最近活跃）
			existing.LastSeen = time.Now()
			existing.IP = node.IP
			existing.Port = node.Port
			b.nodes.MoveToBack(e)
			b.modified = time.Now()
			return
		}
	}

	// 新节点
	if b.nodes.Len() < K {
		// bucket 没满，直接插入
		node.LastSeen = time.Now()
		b.nodes.PushBack(node)
		b.modified = time.Now()
	} else {
		// bucket 满了，检查最旧的节点是否还活着
		oldest := b.nodes.Front()
		if oldest != nil {
			oldNode := oldest.Value.(*NodeInfo)
			if time.Since(oldNode.LastSeen) > 30*time.Minute {
				// 最旧的节点超过 30 分钟没联系，替换它
				b.nodes.Remove(oldest)
				node.LastSeen = time.Now()
				b.nodes.PushBack(node)
				b.modified = time.Now()
			}
			// 否则忽略新节点（Kademlia 规范）
		}
	}
}

// 获取 bucket 中所有节点
func (b *KBucket) All() []*NodeInfo {
	b.RLock()
	defer b.RUnlock()

	nodes := make([]*NodeInfo, 0, b.nodes.Len())
	for e := b.nodes.Front(); e != nil; e = e.Next() {
		nodes = append(nodes, e.Value.(*NodeInfo))
	}
	return nodes
}

// ============================================================
// 路由表
// Routing table
// ============================================================
// 路由表由 160 个 bucket 组成（对应 160 位 NodeID 的每一位）。
// 每个 bucket 负责特定距离范围的节点。
//
// Routing table consists of 160 buckets (one for each bit of the 160-bit NodeID).
// Each bucket handles nodes within a specific distance range.

type RoutingTable struct {
	sync.RWMutex
	selfID  NodeID     // 本节点 ID
	buckets [160]*KBucket // 160 个 bucket
}

func NewRoutingTable(selfID NodeID) *RoutingTable {
	rt := &RoutingTable{selfID: selfID}
	for i := 0; i < 160; i++ {
		rt.buckets[i] = NewKBucket()
	}
	return rt
}

// 计算两个 NodeID 之间的 XOR 距离
func xorDistance(a, b NodeID) *big.Int {
	x := new(big.Int).SetBytes(a[:])
	y := new(big.Int).SetBytes(b[:])
	return new(big.Int).Xor(x, y)
}

// 获取目标节点对应的 bucket 索引（基于 XOR 距离的最高有效位）
func (rt *RoutingTable) bucketIndex(target NodeID) int {
	distance := xorDistance(rt.selfID, target)
	// 找到最高位（第一个为 1 的位）
	return distance.BitLen() - 1
}

// 更新路由表（插入或刷新节点）
func (rt *RoutingTable) Upsert(node *NodeInfo) {
	if node.ID == rt.selfID {
		return // 不插入自己
	}
	idx := rt.bucketIndex(node.ID)
	if idx < 0 {
		idx = 0
	}
	if idx >= 160 {
		idx = 159
	}
	rt.buckets[idx].Upsert(node)
}

// 查找离目标最近的 K 个节点
// Find the K closest nodes to the target
func (rt *RoutingTable) FindClosest(target NodeID) []*NodeInfo {
	rt.RLock()
	defer rt.RUnlock()

	// 收集所有 bucket 中的节点并计算距离
	type nodeDist struct {
		node *NodeInfo
		dist *big.Int
	}

	var candidates []nodeDist
	for i := 0; i < 160; i++ {
		for _, node := range rt.buckets[i].All() {
			dist := xorDistance(node.ID, target)
			candidates = append(candidates, nodeDist{node, dist})
		}
	}

	// 按距离排序，取最近的 K 个
	sort.Slice(candidates, func(i, j int) bool {
		return candidates[i].dist.Cmp(candidates[j].dist) < 0
	})

	if len(candidates) > K {
		candidates = candidates[:K]
	}

	nodes := make([]*NodeInfo, len(candidates))
	for i, cd := range candidates {
		nodes[i] = cd.node
	}
	return nodes
}

// ============================================================
// DHT 节点主结构
// DHT Node
// ============================================================

type DHTNode struct {
	selfID    NodeID
	routing   *RoutingTable
	udpConn   *net.UDPConn
	port      int

	// 已知的引导节点列表（第一次启动时用）
	// Bootstrap nodes (used on first startup)
	bootstrapNodes []string

	// 插件信息索引
	// Plugin info index
	plugins map[string]*PluginInfo // infohash -> plugin info

	// 查询回调（当其他节点请求信息时）
	// Query callbacks (when other nodes request info)
	onFindPlugin func(infohash string) (*PluginInfo, bool)

	quit chan struct{}
	wg   sync.WaitGroup
}

// 插件信息（在 DHT 中传播的元数据）
// Plugin info (metadata propagated in the DHT)
type PluginInfo struct {
	InfoHash   string   `json:"info_hash"`   // 插件唯一哈希（磁力链用）
	Name       string   `json:"name"`         // 插件名
	Version    string   `json:"version"`      // 版本号
	Size       int64    `json:"size"`         // 文件大小
	FileList   []string `json:"file_list"`    // 文件列表
	Author     string   `json:"author"`       // 作者
	Permission int      `json:"permission"`   // 权限等级 0=沙箱 1=签名 2=原生 3=高危
	Signature  string   `json:"signature"`    // 作者签名（可选）
}

// 创建 DHT 节点
func NewDHTNode(selfID NodeID, port int) *DHTNode {
	return &DHTNode{
		selfID:  selfID,
		routing: NewRoutingTable(selfID),
		port:    port,
		plugins: make(map[string]*PluginInfo),
		bootstrapNodes: []string{
			// BitTorrent DHT 引导节点（公开的）
			"router.bittorrent.com:6881",
			"dht.transmissionbt.com:6881",
			"router.utorrent.com:6881",
			// MaidMic 自己的引导节点（可选）
			// "bootstrap.maidmic.dev:6881",
		},
		quit: make(chan struct{}),
	}
}

// ============================================================
// 启动 / 停止
// Start / Stop
// ============================================================

// 启动 DHT 节点
// Start the DHT node
func (d *DHTNode) Start() error {
	addr := &net.UDPAddr{Port: d.port}
	conn, err := net.ListenUDP("udp", addr)
	if err != nil {
		return fmt.Errorf("failed to listen on UDP port %d: %w", d.port, err)
	}
	d.udpConn = conn

	// 启动主循环
	d.wg.Add(1)
	go d.mainLoop()

	// 连接到引导节点
	// Connect to bootstrap nodes
	go d.bootstrap()

	return nil
}

// 停止 DHT 节点
func (d *DHTNode) Stop() {
	close(d.quit)
	d.udpConn.Close()
	d.wg.Wait()
}

// ============================================================
// 主循环：处理 UDP 消息
// Main loop: processes UDP messages
// ============================================================

func (d *DHTNode) mainLoop() {
	defer d.wg.Done()

	buf := make([]byte, 2048)
	for {
		select {
		case <-d.quit:
			return
		default:
			d.udpConn.SetReadDeadline(time.Now().Add(5 * time.Second))
			n, addr, err := d.udpConn.ReadFromUDP(buf)
			if err != nil {
				continue
			}
			d.handleMessage(buf[:n], addr)
		}
	}
}

// ============================================================
// 消息处理
// Message handling
// ============================================================
// DHT 使用 KRPC（Kademlia Remote Procedure Call）协议
// 消息类型: ping, find_node, get_peers, announce_peer

type MessageType int

const (
	MsgPing         MessageType = iota // 探测节点是否在线
	MsgFindNode                        // 查找节点
	MsgGetPeers                        // 获取拥有某个 infohash 的 peers
	MsgAnnouncePeer                    // 宣布拥有某个 infohash
	MsgFindPlugin                      // 查找插件（MaidMic 扩展）
	MsgAnnouncePlugin                  // 宣布插件（MaidMic 扩展）
)

// 消息结构
type Message struct {
	Type      MessageType
	SenderID  NodeID
	TargetID  NodeID    // find_node 的目标
	InfoHash  string    // get_peers / announce_peer / find_plugin 的 infohash
	Token     string    // announce_peer 的验证 token
	Port      int       // 下载端口
	Plugin    *PluginInfo // 插件信息（MaidMic 扩展）
}

func (d *DHTNode) handleMessage(data []byte, addr *net.UDPAddr) {
	// KRPC 协议解析（简化版 — 用 JSON 代替 bencode 方便调试）
	// 生产环境中应该用标准 BitTorrent bencode
	// 这里简化处理

	msg := &Message{}
	// TODO: 实现完整的 KRPC bencode 解析
	// 参考: http://www.bittorrent.org/beps/bep_0005.html

	// 更新路由表
	sender := &NodeInfo{
		ID:        msg.SenderID,
		IP:        addr.IP,
		Port:      addr.Port,
		LastSeen:  time.Now(),
		IsMaidMic: true,
	}
	d.routing.Upsert(sender)

	switch msg.Type {
	case MsgPing:
		d.respondPing(addr, msg)
	case MsgFindNode:
		d.respondFindNode(addr, msg)
	case MsgGetPeers:
		d.respondGetPeers(addr, msg)
	case MsgAnnouncePeer:
		d.handleAnnouncePeer(msg)
	case MsgFindPlugin:
		d.respondFindPlugin(addr, msg)
	case MsgAnnouncePlugin:
		d.handleAnnouncePlugin(msg)
	}
}

// ============================================================
// 消息响应（骨架）
// Message responses (skeleton)
// ============================================================

func (d *DHTNode) respondPing(addr *net.UDPAddr, msg *Message) {
	// 回复 ping 表示自己在线
	// TODO: 发送 KRPC ping 响应
}

func (d *DHTNode) respondFindNode(addr *net.UDPAddr, msg *Message) {
	// 返回离目标最近的 K 个节点
	closest := d.routing.FindClosest(msg.TargetID)
	_ = closest
	// TODO: 发送 find_node 响应
}

func (d *DHTNode) respondGetPeers(addr *net.UDPAddr, msg *Message) {
	// 如果有这个 infohash 的 peers，返回它们
	// 否则返回离 infohash 最近的 K 个节点
	_ = msg.InfoHash
	// TODO: 发送 get_peers 响应
}

func (d *DHTNode) handleAnnouncePeer(msg *Message) {
	// 保存某个节点宣布拥有的 infohash
	_ = msg.InfoHash
	// TODO: 保存到本地 peer 数据库
}

func (d *DHTNode) respondFindPlugin(addr *net.UDPAddr, msg *Message) {
	if d.onFindPlugin != nil {
		plugin, ok := d.onFindPlugin(msg.InfoHash)
		if ok {
			_ = plugin
			// TODO: 发送插件信息
		}
	}
}

func (d *DHTNode) handleAnnouncePlugin(msg *Message) {
	if msg.Plugin != nil {
		d.plugins[msg.Plugin.InfoHash] = msg.Plugin
		_ = len(d.plugins)
	}
}

// ============================================================
// 引导连接
// Bootstrap connection
// ============================================================

func (d *DHTNode) bootstrap() {
	for _, addr := range d.bootstrapNodes {
		// 向引导节点发送 find_node（查找自己）
		// Send find_node to bootstrap node
		udpAddr, err := net.ResolveUDPAddr("udp", addr)
		if err != nil {
			continue
		}
		_ = udpAddr
		// TODO: 发送 KRPC find_node 消息
	}
}

// ============================================================
// 插件搜索 API（供上层调用）
// Plugin search API (for upper layer)
// ============================================================

// SearchPlugin 在 DHT 网络中搜索指定 infohash 的插件
// Search for a plugin with the given infohash in the DHT network
func (d *DHTNode) SearchPlugin(infohash string) (*PluginInfo, error) {
	// 先查本地缓存
	if plugin, ok := d.plugins[infohash]; ok {
		return plugin, nil
	}

	// 向 DHT 网络发起 find_plugin 查询
	closest := d.routing.FindClosest(NodeID{}) // TODO: 用 infohash 的哈希作为目标
	_ = closest
	// TODO: 向最近的 K 个节点发送 find_plugin 请求

	return nil, fmt.Errorf("plugin %s not found", infohash)
}

// AnnouncePlugin 在 DHT 网络中宣布自己拥有某个插件
// Announce that this node has a plugin in the DHT network
func (d *DHTNode) AnnouncePlugin(plugin *PluginInfo) {
	d.plugins[plugin.InfoHash] = plugin
	// TODO: 向 K 个最近节点发送 announce_plugin 消息
}
