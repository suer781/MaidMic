-- chipmunk.lua — 花栗鼠效果插件
-- ============================================================
-- 高音高 + 上移共振峰的经典"花栗鼠"音色。
-- 引擎 v3 的 PSOLA 变调在大偏移下共振峰保持，花栗鼠效果比旧版自然得多。

plugin_info = {
    name = "花栗鼠",
    author = "MaidMic",
    version = 1,
    description = "高音高小动物音色：+7 半音、共振峰上移"
}

function activate()
    maidmic.log("花栗鼠插件激活")
    maidmic.set_param("pitch_semitones", 7)   -- 音高 +7 半音
    maidmic.set_param("formant_shift", 3.0)   -- 共振峰上移（声道变短）
    maidmic.set_param("distortion", 0.0)
    maidmic.set_param("reverb_mix", 0.0)
    maidmic.set_param("echo_delay_ms", 0.0)
    maidmic.set_param("echo_decay", 0.0)
end

function deactivate()
    maidmic.log("花栗鼠插件停用")
    maidmic.set_param("pitch_semitones", 0)
    maidmic.set_param("formant_shift", 0.0)
end
