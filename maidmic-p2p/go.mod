// maidmic-p2p/go.mod
// MaidMic P2P/PCDN 引擎
// 
// 用 Go 实现 P2P 网络层，通过 gomobile 编译为 Android aar。
// 负责：
// 1. DHT 网络发现（基于 Kademlia）
// 2. 磁力链/BitTorrent 协议下载
// 3. GitHub 插件索引同步
// 4. 插件分片的 PCDN 分发

module github.com/maidmic/p2p

go 1.22

require (
	// 计划使用的库：
	// github.com/anacrolix/torrent v1.55.0  // BitTorrent 客户端
	// github.com/libp2p/go-libp2p v0.33.0   // P2P 网络层
	// github.com/google/uuid v1.6.0         // UUID 生成
)
