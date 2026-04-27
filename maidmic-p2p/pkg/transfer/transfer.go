// maidmic-p2p/pkg/transfer/transfer.go
// MaidMic P2P 传输引擎
// ============================================================
// 负责插件的 P2P 分片下载（PCDN）。
//
// 核心思路：
// 1. 插件文件被切成 256KB 的分片 (piece)
// 2. 每个分片有独立的 SHA1 哈希
// 3. 多个 peers 可以同时提供不同分片
// 4. 下载完成的分片也上传给其他人（PCDN 精神）
//
// 这样即使原始发布者不在线，只要有其他用户下载过，
// 新用户就能从他们那里获取分片。

package transfer

import (
	"crypto/sha1"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sync"
)

// ============================================================
// 常量
// Constants
// ============================================================

const (
	// 每个分片的大小：256KB
	// Piece size: 256KB — 平衡了分片数量和传输效率
	PieceSize = 256 * 1024

	// 同时下载的分片数（并发）
	// Concurrent piece downloads
	MaxConcurrentPieces = 4

	// 分片下载超时
	// Piece download timeout
	PieceTimeout = 30 // 秒
)

// ============================================================
// 分片信息
// Piece info
// ============================================================

// PieceInfo 描述一个分片
// Describes a single piece
type PieceInfo struct {
	Index    int    // 分片序号
	Size     int64  // 分片大小（最后一片可能不满 PieceSize）
	Hash     string // SHA1 哈希（十六进制）
	Data     []byte // 分片数据（下载完成后填充）
	Downloaded bool // 是否已下载
}

// TorrentMeta 描述一个完整的插件文件
// Describes a complete plugin file
type TorrentMeta struct {
	InfoHash string      // 整体文件哈希（磁力链用）
	Name     string      // 文件名
	Size     int64       // 文件大小
	Pieces   []PieceInfo // 所有分片
}

// ============================================================
// 传输引擎
// Transfer engine
// ============================================================

type TransferEngine struct {
	mu       sync.RWMutex
	downloads map[string]*DownloadTask // infohash -> download task
	basePath string                     // 插件存储根目录
}

type DownloadTask struct {
	Meta    *TorrentMeta
	Peers   []PeerInfo     // 已知拥有此插件的 peers
	Pieces  []PieceInfo    // 分片状态
	OnPiece func(index int) // 分片完成回调
	OnDone  func()          // 全部完成回调
	mu      sync.Mutex
}

// PeerInfo 描述一个对等节点
// Describes a peer
type PeerInfo struct {
	ID   string // 节点 ID
	IP   string // IP 地址
	Port int    // 传输端口
}

// ============================================================
// 创建传输引擎
// Create transfer engine
// ============================================================

func NewTransferEngine(basePath string) *TransferEngine {
	os.MkdirAll(basePath, 0755)
	return &TransferEngine{
		downloads: make(map[string]*DownloadTask),
		basePath:  basePath,
	}
}

// ============================================================
// 从文件创建 Torrent 元数据
// Create Torrent metadata from a file
// ============================================================

// CreateTorrent 读取文件并生成分片哈希列表
// Read a file and generate piece hash list
func (te *TransferEngine) CreateTorrent(filePath string) (*TorrentMeta, error) {
	file, err := os.Open(filePath)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	stat, err := file.Stat()
	if err != nil {
		return nil, err
	}

	fileName := filepath.Base(filePath)
	fileSize := stat.Size()
	numPieces := int((fileSize + PieceSize - 1) / PieceSize)

	meta := &TorrentMeta{
		Name:   fileName,
		Size:   fileSize,
		Pieces: make([]PieceInfo, numPieces),
	}

	// 读取文件并计算每个分片的哈希
	// Read file and compute hash for each piece
	buf := make([]byte, PieceSize)
	for i := 0; i < numPieces; i++ {
		n, err := file.Read(buf)
		if err != nil && err != io.EOF {
			return nil, err
		}

		pieceData := buf[:n]
		hash := sha1.Sum(pieceData)

		meta.Pieces[i] = PieceInfo{
			Index:      i,
			Size:       int64(n),
			Hash:       hex.EncodeToString(hash[:]),
			Data:       nil, // 只在下载时填充
			Downloaded: true, // 本地的文件所有分片都已存在
		}
	}

	// 计算整体 InfoHash
	// Compute overall InfoHash
	hash := sha1.New()
	for _, piece := range meta.Pieces {
		pieceHash, _ := hex.DecodeString(piece.Hash)
		hash.Write(pieceHash)
	}
	meta.InfoHash = hex.EncodeToString(hash.Sum(nil))

	return meta, nil
}

// ============================================================
// 启动下载任务
// Start a download task
// ============================================================

// StartDownload 开始从 peers 下载插件
// Start downloading a plugin from peers
func (te *TransferEngine) StartDownload(meta *TorrentMeta, peers []PeerInfo) error {
	te.mu.Lock()
	defer te.mu.Unlock()

	// 检查是否已经在下载
	if _, exists := te.downloads[meta.InfoHash]; exists {
		return fmt.Errorf("already downloading %s", meta.InfoHash)
	}

	// 创建下载任务，所有分片标记为未下载
	// Create download task, mark all pieces as not downloaded
	task := &DownloadTask{
		Meta:   meta,
		Peers:  peers,
		Pieces: make([]PieceInfo, len(meta.Pieces)),
	}
	for i, p := range meta.Pieces {
		task.Pieces[i] = PieceInfo{
			Index:      p.Index,
			Size:       p.Size,
			Hash:       p.Hash,
			Downloaded: false,
		}
	}

	te.downloads[meta.InfoHash] = task

	// 启动下载协程
	// Start download goroutines
	go te.downloadLoop(task)

	return nil
}

// ============================================================
// 下载循环
// Download loop
// ============================================================
// 核心算法：选择最快的 peer，请求尚未下载的分片。
// 类似于 BitTorrent 的"端到端选择性下载"。
//
// Core algorithm: pick the fastest peer, request undownloaded pieces.
// Similar to BitTorrent's "end-to-end selective download".

func (te *TransferEngine) downloadLoop(task *DownloadTask) {
	// TODO: 实现完整的 P2P 分片选择 + 下载 + 验证 + 持久化
	//
	// 伪代码:
	// for 所有分片未完成 {
	//   for i = 0; i < MaxConcurrentPieces; i++ {
	//     选择最快的 peer
	//     发送 piece 请求 (index, offset, length)
	//     接收 piece 数据
	//     验证 SHA1 哈希
	//     标记完成
	//     触发 OnPiece 回调
	//   }
	// }
	//
	// 下载完成:
	//   重组文件到 basePath/<infohash>/<filename>
	//   触发 OnDone 回调

	_ = task
}

// ============================================================
// 分片上传（给其他 peer 提供数据）
// Piece upload (serve data to other peers)
// ============================================================

// ServePiece 向请求者提供某个分片的数据
// Serve a piece of data to a requester
func (te *TransferEngine) ServePiece(infohash string, pieceIndex int) ([]byte, error) {
	// 从 basePath/<infohash>/ 目录中读取文件，返回指定分片
	// Read file from basePath/<infohash>/ and return requested piece
	te.mu.RLock()
	defer te.mu.RUnlock()

	pluginDir := filepath.Join(te.basePath, infohash)
	entries, err := os.ReadDir(pluginDir)
	if err != nil {
		return nil, err
	}

	if len(entries) == 0 {
		return nil, fmt.Errorf("no files in %s", pluginDir)
	}

	// 假设目录下只有一个文件
	filePath := filepath.Join(pluginDir, entries[0].Name)
	file, err := os.Open(filePath)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	offset := int64(pieceIndex) * PieceSize
	file.Seek(offset, io.SeekStart)

	data := make([]byte, PieceSize)
	n, err := file.Read(data)
	if err != nil && err != io.EOF {
		return nil, err
	}

	return data[:n], nil
}
