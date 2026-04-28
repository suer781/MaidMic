// maidmic-p2p/pkg/index/index.go
// MaidMic GitHub 插件索引同步
// ============================================================
// 插件市场的元数据存储在 GitHub 仓库中，格式为 index.json。
// 每个插件在索引中有一条记录，包含：
//   - 插件名、描述、作者
//   - 版本号、文件大小
//   - 权限等级
//   - 源代码 GitHub 链接
//
// 客户端定期拉取 index.json，对比本地已安装版本，提示更新。
// 下载走 GitHub Releases 直连下载。

package index

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// ============================================================
// 插件索引条目
// Plugin index entry
// ============================================================

// PluginEntry 描述插件市场中一个插件的完整信息
// Describes a complete plugin in the marketplace
type PluginEntry struct {
	// 基础信息
	ID          string `json:"id"`          // 唯一标识（通常是 github.com/author/plugin）
	Name        string `json:"name"`        // 展示名称
	Description string `json:"description"` // 简短描述
	Author      string `json:"author"`      // 作者

	// 版本信息
	Version     string `json:"version"`     // 语义化版本号
	UpdatedAt   string `json:"updated_at"`  // 最后更新日期

	// 下载信息
	FileSize    int64  `json:"file_size"`    // 文件大小（字节）
	FileHash    string `json:"file_hash"`    // SHA256 哈希（验证完整性）

	// 来源
	SourceURL   string `json:"source_url"`   // GitHub 源码链接
	DownloadURL string `json:"download_url"` // 直接下载链接（P2P 失败时回退）

	// 权限
	PermissionLevel int `json:"permission_level"` // 0=沙箱 1=签名 2=原生 3=高危

	// 标签
	Tags []string `json:"tags"` // 标签：["变声", "混响", "免费", ...]

	// 预设（可选）
	Presets map[string]interface{} `json:"presets,omitempty"` // 预设参数
}

// PluginIndex 完整的插件索引
// Complete plugin index
type PluginIndex struct {
	Version   int            `json:"version"`    // 索引格式版本
	UpdatedAt string         `json:"updated_at"` // 索引生成时间
	Plugins   []PluginEntry  `json:"plugins"`    // 插件列表
}

// ============================================================
// 索引同步器
// Index synchronizer
// ============================================================

type IndexSync struct {
	mu           sync.RWMutex
	indexURL     string          // 索引文件 URL（GitHub raw）
	localPath    string          // 本地缓存路径
	cachedIndex  *PluginIndex    // 缓存的索引
	lastSync     time.Time       // 最后同步时间
	updateInterval time.Duration // 同步间隔（默认 1 小时）
}

func NewIndexSync(indexURL, localPath string) *IndexSync {
	return &IndexSync{
		indexURL:       indexURL,
		localPath:      localPath,
		updateInterval: 1 * time.Hour,
	}
}

// ============================================================
// 核心方法
// Core methods
// ============================================================

// Sync 从 GitHub 拉取最新的插件索引
// Fetch the latest plugin index from GitHub
func (is *IndexSync) Sync() error {
	// 下载索引文件
	// Download the index file
	resp, err := http.Get(is.indexURL)
	if err != nil {
		return fmt.Errorf("failed to fetch index: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("index server returned %d", resp.StatusCode)
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Errorf("failed to read index: %w", err)
	}

	// 解析 JSON
	var index PluginIndex
	if err := json.Unmarshal(body, &index); err != nil {
		return fmt.Errorf("failed to parse index: %w", err)
	}

	// 写入本地缓存
	// Write to local cache
	os.MkdirAll(filepath.Dir(is.localPath), 0755)
	if err := os.WriteFile(is.localPath, body, 0644); err != nil {
		return fmt.Errorf("failed to cache index: %w", err)
	}

	is.mu.Lock()
	is.cachedIndex = &index
	is.lastSync = time.Now()
	is.mu.Unlock()

	return nil
}

// LoadLocal 从本地缓存加载索引（离线也能用）
// Load index from local cache (works offline)
func (is *IndexSync) LoadLocal() error {
	data, err := os.ReadFile(is.localPath)
	if err != nil {
		return err
	}

	var index PluginIndex
	if err := json.Unmarshal(data, &index); err != nil {
		return err
	}

	is.mu.Lock()
	is.cachedIndex = &index
	is.mu.Unlock()

	return nil
}

// ============================================================
// 查询方法
// Query methods
// ============================================================

// Search 搜索插件（按名称/作者/标签模糊匹配）
// Search plugins by name/author/tags
func (is *IndexSync) Search(query string) []PluginEntry {
	is.mu.RLock()
	defer is.mu.RUnlock()

	if is.cachedIndex == nil {
		return nil
	}

	query = strings.ToLower(query)
	var results []PluginEntry

	for _, plugin := range is.cachedIndex.Plugins {
		if strings.Contains(strings.ToLower(plugin.Name), query) ||
			strings.Contains(strings.ToLower(plugin.Author), query) ||
			strings.Contains(strings.ToLower(plugin.Description), query) {
			results = append(results, plugin)
		}
		// 标签匹配
		for _, tag := range plugin.Tags {
			if strings.Contains(strings.ToLower(tag), query) {
				results = append(results, plugin)
				break
			}
		}
	}

	return results
}

// GetByID 按 ID 获取插件详情
// Get plugin detail by ID
func (is *IndexSync) GetByID(id string) (*PluginEntry, bool) {
	is.mu.RLock()
	defer is.mu.RUnlock()

	if is.cachedIndex == nil {
		return nil, false
	}

	for _, plugin := range is.cachedIndex.Plugins {
		if plugin.ID == id {
			return &plugin, true
		}
	}
	return nil, false
}

// ListByPermission 按权限等级列出插件
// List plugins by permission level
func (is *IndexSync) ListByPermission(level int) []PluginEntry {
	is.mu.RLock()
	defer is.mu.RUnlock()

	if is.cachedIndex == nil {
		return nil
	}

	var results []PluginEntry
	for _, plugin := range is.cachedIndex.Plugins {
		if plugin.PermissionLevel <= level {
			results = append(results, plugin)
		}
	}
	return results
}

// ListAll 列出所有插件（按更新时间排序）
// List all plugins (sorted by update time)
func (is *IndexSync) ListAll() []PluginEntry {
	is.mu.RLock()
	defer is.mu.RUnlock()

	if is.cachedIndex == nil {
		return nil
	}

	plugins := make([]PluginEntry, len(is.cachedIndex.Plugins))
	copy(plugins, is.cachedIndex.Plugins)

	sort.Slice(plugins, func(i, j int) bool {
		return plugins[i].UpdatedAt > plugins[j].UpdatedAt
	})

	return plugins
}

// ============================================================
// 文件完整性验证
// File integrity verification
// ============================================================

// VerifyFile 验证下载的插件文件 SHA256 是否匹配
// Verify downloaded plugin file SHA256 matches
func VerifyFile(filePath, expectedHash string) (bool, error) {
	file, err := os.Open(filePath)
	if err != nil {
		return false, err
	}
	defer file.Close()

	hasher := sha256.New()
	if _, err := io.Copy(hasher, file); err != nil {
		return false, err
	}

	actualHash := hex.EncodeToString(hasher.Sum(nil))
	return strings.EqualFold(actualHash, expectedHash), nil
}
