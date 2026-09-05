-- deep_uncle.lua — 低沉大叔效果插件
-- ============================================================
-- 压低音高 + 下移共振峰 + 低频增厚，成熟男声。
-- 展示 maidmic.get_param 的用法：激活前先读当前值，停用时精确恢复。

plugin_info = {
    name = "低沉大叔",
    author = "MaidMic",
    version = 1,
    description = "成熟男声：压低音高与共振峰，低频增厚"
}

local saved = {}

local function remember(key)
    if saved[key] == nil then
        saved[key] = maidmic.get_param(key)
    end
end

function activate()
    maidmic.log("低沉大叔插件激活")
    remember("pitch_semitones")
    remember("formant_shift")
    remember("bass_db")
    remember("distortion")

    maidmic.set_param("pitch_semitones", -5)   -- 音高 -5 半音
    maidmic.set_param("formant_shift", -3.0)   -- 共振峰下移（声道变长）
    maidmic.set_param("bass_db", 3.0)          -- 低频增厚
    maidmic.set_param("distortion", 0.0)
    maidmic.set_param("treble_db", 0.0)
end

function deactivate()
    maidmic.log("低沉大叔插件停用，恢复原参数")
    for key, value in pairs(saved) do
        maidmic.set_param(key, value)
    end
    saved = {}
end
