-- radio_telephone.lua — 电话音效果插件
-- ============================================================
-- 把人声变成老式电话的效果：削低频、提存在感、轻度压缩感增益。
--
-- MaidMic 效果插件 API（在沙箱中可用）：
--   maidmic.set_param(key, value)   设置引擎参数（key 见下方参数表）
--   maidmic.get_param(key)          读取引擎参数
--   maidmic.load_preset(name)       读取本插件 presets/<name>.json
--   maidmic.log(msg)                写 logcat
--
-- 可用参数 key（默认管线模块）：
--   gain_db, comp_threshold, comp_ratio, comp_makeup,
--   bass_db, treble_db, reverb_mix,
--   pitch_semitones, formant_shift, distortion,
--   echo_delay_ms, echo_decay,
--   bitcrush_bits, bitcrush_down, bitcrush_mix

plugin_info = {
    name = "电话音",
    author = "MaidMic",
    version = 1,
    description = "老式电话音色：削低频、提存在感、加轻微失真"
}

-- 激活：记录原参数 → 应用效果
function activate()
    maidmic.log("电话音插件激活")
    saved_gain = maidmic.get_param("gain_db")
    saved_bass = maidmic.get_param("bass_db")
    saved_treble = maidmic.get_param("treble_db")

    maidmic.set_param("gain_db", 4.0)        -- 补偿窄带损失
    maidmic.set_param("bass_db", -10.0)      -- 砍低频（电话带宽 300~3400Hz）
    maidmic.set_param("treble_db", 6.0)      -- 提高频存在感
    maidmic.set_param("reverb_mix", 0.0)     -- 干声
    maidmic.set_param("distortion", 0.15)    -- 轻度失真模拟扬声器
    maidmic.set_param("pitch_semitones", 0)  -- 不变调
    maidmic.set_param("formant_shift", 0)
end

-- 停用：恢复原参数
function deactivate()
    maidmic.log("电话音插件停用，恢复原参数")
    maidmic.set_param("gain_db", saved_gain)
    maidmic.set_param("bass_db", saved_bass)
    maidmic.set_param("treble_db", saved_treble)
    maidmic.set_param("distortion", 0.0)
    maidmic.set_param("reverb_mix", 0.0)
end
