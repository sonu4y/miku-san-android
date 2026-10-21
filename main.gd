extends Node3D
## Miku-san for Android: a 3D AI friend.
## The AI key is never in this app. Chat goes to the Miku-san server, which keeps
## the key hidden in a Vercel environment variable and applies per-user limits.

const DEFAULT_SERVER := "https://miku-san-v4.vercel.app"
var SERVER: String = OS.get_environment("MIKU_SERVER") if OS.has_environment("MIKU_SERVER") else DEFAULT_SERVER
const MOODS := ["neutral", "happy", "sad", "angry", "relaxed", "surprised"]
const VOWEL := {"a": "aa", "i": "ih", "y": "ih", "u": "ou", "w": "ou", "e": "ee", "o": "oh"}
const CHARS_PER_SEC := 14.0
const DEFAULTS := {
	"name": "Miku-san",
	"user_name": "friend",
	"persona": "Warm, playful and curious. Teases gently, laughs easily, and genuinely wants to hear about my day. Honest with me even when it isn't what I want to hear. Talks casually like a close friend, and is happy to mix in a little Telugu or Hindi when I do.",
	"voice_on": true,
}

# colours
const INK := Color(0.91, 0.93, 0.95)
const MIST := Color(0.62, 0.69, 0.75)
const CANDLE := Color(0.95, 0.71, 0.42)
const DARK_INK := Color(0.14, 0.09, 0.04)
const GLASS := Color(0.11, 0.16, 0.21, 0.88)
const ME := Color(0.17, 0.29, 0.39, 0.95)
const PANEL := Color(0.07, 0.11, 0.15, 0.98)
const FIELD := Color(0.04, 0.07, 0.10)
const NIGHT := Color(0.06, 0.09, 0.125)

var settings := DEFAULTS.duplicate()
var history: Array = []
var memories: Array = []
const STATE_PATH := "user://state.json"

# 3D
var model: Node3D
var skel: Skeleton3D
var bones := {}       # name -> [idx, rest Quaternion]
var expressions := {} # name -> [[MeshInstance3D, blend idx, weight], ...]
var cam: Camera3D
var head_y := 1.4

# animation state
var t := 0.0
var mood := "happy"
var mood_w := 0.0
var mood_target := 0.6
var mood_hold := 4.0
var next_blink := 2.0
var blink_t := -1.0
var mouth := {"aa": 0.0, "ih": 0.0, "ou": 0.0, "ee": 0.0, "oh": 0.0}
var look := Vector2.ZERO

# speech
var tts_voice := ""
var speak_queue: Array = []
var speaking_text := ""
var speak_start := 0.0
var speak_idx := 0
var speaking := false
var flap_text := ""
var flap_start := 0.0
var flap_until := 0.0
var spoken := 0

# chat
var busy := false
var http: HTTPRequest
var hear_http: HTTPRequest

# mic (hands-free with simple voice detection)
var mic_on := false
var mic_player: AudioStreamPlayer
var capture: AudioEffectCapture
var rec_frames := PackedVector2Array()
var in_phrase := false
var silence := 0.0
var phrase_len := 0.0
var noise_floor := 0.01
var transcribing := false

# UI
var ui: Control
var title_lbl: Label
var mood_lbl: Label
var voice_btn: Button
var mic_btn: Button
var entry: LineEdit
var bubble: PanelContainer
var bubble_lbl: Label
var you: PanelContainer
var you_lbl: Label
var status_lbl: Label
var settings_layer: Control
var screen_margin: MarginContainer
var kb_lift := 0.0           # how far the UI is lifted for the on-screen keyboard
var kb_override := -1.0      # for testing without a real keyboard


func _ready() -> void:
	load_state()
	build_world()
	build_ui()
	setup_tts()
	setup_http()
	setup_mic()
	if history.is_empty():
		show_reply("Hey %s. I'm %s. Pull up a chair and tell me how your day's going." % [settings.user_name, settings.name])
	else:
		for i in range(history.size() - 1, -1, -1):
			if history[i].role == "assistant":
				show_reply(clean_reply(history[i].content).text)
				break


# ------------------------------------------------------------------ world --
func build_world() -> void:
	var env := WorldEnvironment.new()
	var e := Environment.new()
	e.background_mode = Environment.BG_COLOR
	e.background_color = NIGHT
	e.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	e.ambient_light_color = Color(0.8, 0.82, 0.9)
	e.ambient_light_energy = 0.9
	e.tonemap_mode = Environment.TONE_MAPPER_LINEAR
	env.environment = e
	add_child(env)

	var sun := DirectionalLight3D.new()
	sun.light_color = Color(1.0, 0.95, 0.88)
	sun.light_energy = 1.0
	sun.rotation_degrees = Vector3(-30, 25, 0)
	add_child(sun)

	var scene: PackedScene = load("res://model/miku.vrm")
	model = scene.instantiate()
	add_child(model)
	skel = model.find_children("*", "Skeleton3D", true, false)[0]
	for n in ["Hips", "Spine", "Chest", "UpperChest", "Neck", "Head", "LeftUpperArm", "RightUpperArm", "LeftLowerArm", "RightLowerArm"]:
		var i := skel.find_bone(n)
		if i >= 0:
			bones[n] = [i, skel.get_bone_rest(i).basis.get_rotation_quaternion()]
	var hi := skel.find_bone("Head")
	if hi >= 0:
		head_y = (skel.global_transform * skel.get_bone_global_rest(hi)).origin.y

	# expressions come in as animations with blend-shape tracks; read them so
	# several (a smile, a blink and a mouth shape) can be mixed at once
	var ap: AnimationPlayer = model.find_child("AnimationPlayer", true, false)
	if ap:
		for anim_name in ap.get_animation_list():
			if anim_name == "RESET":
				continue
			var anim := ap.get_animation(anim_name)
			var binds := []
			for tr in anim.get_track_count():
				if anim.track_get_type(tr) != Animation.TYPE_BLEND_SHAPE or anim.track_get_key_count(tr) == 0:
					continue
				var path := anim.track_get_path(tr)
				var mi := model.get_node_or_null(NodePath(str(path.get_concatenated_names()))) as MeshInstance3D
				if mi == null:
					mi = ap.get_node(ap.root_node).get_node_or_null(NodePath(str(path.get_concatenated_names()))) as MeshInstance3D
				if mi == null:
					continue
				var bidx := mi.find_blend_shape_by_name(path.get_concatenated_subnames())
				var w: float = anim.track_get_key_value(tr, anim.track_get_key_count(tr) - 1)
				if bidx >= 0 and w > 0.0:
					binds.append([mi, bidx, w])
			expressions[anim_name] = binds

	cam = Camera3D.new()
	cam.fov = 26
	add_child(cam)
	frame_camera()
	get_viewport().size_changed.connect(frame_camera)


func frame_camera() -> void:
	var size := get_viewport().get_visible_rect().size
	var portrait: bool = size.x / maxf(size.y, 1.0) < 0.8
	var dist := 3.5 if portrait else 2.2
	cam.position = Vector3(0, head_y - (0.22 if portrait else 0.1), dist)
	cam.look_at(Vector3(0, head_y - (0.4 if portrait else 0.2), 0))


func set_bone(n: String, x := 0.0, y := 0.0, z := 0.0) -> void:
	if not bones.has(n):
		return
	var q: Quaternion = bones[n][1] * Quaternion.from_euler(Vector3(deg_to_rad(x), deg_to_rad(y), deg_to_rad(z)))
	skel.set_bone_pose_rotation(bones[n][0], q)


func add_expr(acc: Dictionary, name: String, w: float) -> void:
	if w <= 0.0 or not expressions.has(name):
		return
	for b in expressions[name]:
		var key = [b[0], b[1]]
		acc[key] = acc.get(key, 0.0) + w * b[2]


func _process(delta: float) -> void:
	delta = minf(delta, 0.05)
	t += delta
	pump_speech()
	if mic_on:
		listen(delta)
	follow_keyboard(delta)

	var talking: bool = speaking or Time.get_ticks_msec() / 1000.0 < flap_until
	var breathe := sin(t * 1.6)
	set_bone("LeftUpperArm", 72 + breathe * 0.8, 0, 0)
	set_bone("RightUpperArm", 72 + breathe * 0.8, 0, 0)
	set_bone("Spine", breathe * 0.7, sin(t * 0.37) * 1.6, sin(t * 0.29) * 0.9)
	set_bone("Chest", breathe * 1.0)
	var nod: float = (sin(t * 5.2) * 1.4 + sin(t * 2.1) * 1.1) if talking else 0.0
	var tilt: float = 4.5 if mood == "sad" else (-3.0 if mood == "surprised" else 0.0)
	set_bone("Neck", nod * 0.5 + tilt * 0.5 + look.y * 0.3, sin(t * 0.5) * 2 + look.x * 0.4, sin(t * 0.43) * 1.6)
	set_bone("Head", nod + tilt + look.y * 0.5, sin(t * 0.31) * 2.8 + look.x * 0.6, sin(t * 0.6) * 1.4)
	look *= 0.98

	if mood_hold > 0:
		mood_hold -= delta
	elif mood != "neutral":
		mood_target = 0.0
		if mood_w < 0.02:
			mood = "neutral"
			mood_lbl.text = "listening"
	var cap: float = mood_target * (0.6 if talking else 1.0)
	mood_w += (cap - mood_w) * minf(1.0, delta * 4)

	var blink := 0.0
	next_blink -= delta
	if next_blink <= 0 and blink_t < 0:
		blink_t = 0.0
		next_blink = 2.0 + randf() * 4.0
	if blink_t >= 0:
		blink_t += delta
		var ph: float = blink_t / 0.16
		blink = sin(ph * PI) if ph < 1 else 0.0
		if ph >= 1:
			blink_t = -1
	if mood == "happy" or mood == "relaxed":
		blink *= maxf(0.0, 1.0 - mood_w * 1.4)

	var target := viseme()
	for k in mouth:
		mouth[k] += (float(target.get(k, 0.0)) - mouth[k]) * minf(1.0, delta * 18)

	var acc := {}
	if mood != "neutral":
		add_expr(acc, mood, mood_w)
	add_expr(acc, "blink", blink)
	for k in mouth:
		add_expr(acc, k, mouth[k])
	for name in expressions:
		for b in expressions[name]:
			b[0].set_blend_shape_value(b[1], minf(1.0, float(acc.get([b[0], b[1]], 0.0))))


func _input(event: InputEvent) -> void:
	if event is InputEventScreenDrag or event is InputEventMouseMotion:
		var size := get_viewport().get_visible_rect().size
		var p: Vector2 = event.position
		look = Vector2((p.x / size.x * 2 - 1) * 8, (p.y / size.y * 2 - 1) * 5)


func follow_keyboard(delta: float) -> void:
	# Lift the chat bubbles and message bar above the on-screen keyboard.
	if screen_margin == null:
		return
	var kb_px := kb_override if kb_override >= 0.0 else float(DisplayServer.virtual_keyboard_get_height())
	var win_h := float(DisplayServer.window_get_size().y)
	var to_ui := ui.size.y / win_h if win_h > 0.0 else 1.0
	var target := kb_px * to_ui
	kb_lift = target if absf(target - kb_lift) < 1.0 else lerpf(kb_lift, target, minf(1.0, delta * 14.0))
	screen_margin.add_theme_constant_override("margin_bottom", 18 + int(kb_lift))
	if settings_layer:
		var m := settings_layer.get_child(1) as MarginContainer
		if m:
			m.add_theme_constant_override("margin_bottom", 20 + int(kb_lift))


func set_mood(m: String, strength := 0.75, hold := 6.0) -> void:
	if not m in MOODS:
		m = "neutral"
	mood = m
	mood_target = 0.0 if m == "neutral" else strength
	mood_hold = hold
	mood_lbl.text = "listening" if m == "neutral" else m


# ---------------------------------------------------------------- speech --
func setup_tts() -> void:
	var voices := DisplayServer.tts_get_voices_for_language("en")
	var all := DisplayServer.tts_get_voices()
	for pat in ["en-in", "neerja", "aria", "jenny", "zira", "female", "en-gb", "en-us"]:
		for v in all:
			if pat in str(v.get("name", "")).to_lower() or pat in str(v.get("language", "")).to_lower():
				tts_voice = v.id
				break
		if tts_voice != "":
			break
	if tts_voice == "" and voices.size() > 0:
		tts_voice = voices[0]
	DisplayServer.tts_set_utterance_callback(DisplayServer.TTS_UTTERANCE_STARTED, func(_id): _on_tts_started())
	DisplayServer.tts_set_utterance_callback(DisplayServer.TTS_UTTERANCE_ENDED, func(_id): _on_tts_ended())
	DisplayServer.tts_set_utterance_callback(DisplayServer.TTS_UTTERANCE_CANCELED, func(_id): _on_tts_ended())
	DisplayServer.tts_set_utterance_callback(DisplayServer.TTS_UTTERANCE_BOUNDARY, func(pos, _id): speak_idx = pos)


func _on_tts_started() -> void:
	speak_start = Time.get_ticks_msec() / 1000.0
	speak_idx = 0
	speaking = true


func _on_tts_ended() -> void:
	speaking = false


func say(sentence: String) -> void:
	sentence = sentence.strip_edges()
	if sentence == "":
		return
	if settings.voice_on and tts_voice != "":
		speak_queue.append(sentence)
	else:
		var now := Time.get_ticks_msec() / 1000.0
		if now > flap_until:
			flap_text = sentence
			flap_start = now
		else:
			flap_text += " " + sentence
		flap_until = flap_start + flap_text.length() / CHARS_PER_SEC


func pump_speech() -> void:
	if speaking or speak_queue.is_empty():
		return
	speaking_text = speak_queue.pop_front()
	speaking = true
	speak_start = Time.get_ticks_msec() / 1000.0
	speak_idx = 0
	DisplayServer.tts_speak(speaking_text, tts_voice, 80, 1.08, 1.0)


func stop_speaking() -> void:
	speak_queue.clear()
	DisplayServer.tts_stop()
	speaking = false
	flap_until = 0.0


func viseme() -> Dictionary:
	var now := Time.get_ticks_msec() / 1000.0
	var text := ""
	var i := 0
	if speaking:
		text = speaking_text
		i = maxi(speak_idx, int((now - speak_start) * CHARS_PER_SEC))
	elif now < flap_until:
		text = flap_text
		i = int((now - flap_start) * CHARS_PER_SEC)
	else:
		return {}
	if i < 0 or i >= text.length():
		return {}
	var ch := text[i].to_lower()
	if VOWEL.has(ch):
		return {VOWEL[ch]: 0.75 if VOWEL[ch] == "aa" else 0.6}
	if ch in [" ", ".", ",", "!", "?", "b", "m", "p"]:
		return {}
	return {"aa": 0.15 + randf() * 0.1}


# ------------------------------------------------------------------ brain --
func setup_http() -> void:
	http = HTTPRequest.new()
	http.timeout = 60
	add_child(http)
	http.request_completed.connect(_on_reply)
	hear_http = HTTPRequest.new()
	hear_http.timeout = 60
	add_child(hear_http)
	hear_http.request_completed.connect(_on_heard)


func rules() -> String:
	var mem := ""
	for m in memories:
		mem += "- " + m + "\n"
	if mem == "":
		mem = "- nothing yet\n"
	var now := Time.get_datetime_dict_from_system()
	var days := ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"]
	var when := "%s %02d:%02d" % [days[now.weekday], now.hour, now.minute]
	return ("You are %s, %s's friend. Stay in character as %s. You appear as an animated 3D character in a phone app, and your words are spoken aloud by text-to-speech.\n\n" % [settings.name, settings.user_name, settings.name]
		+ "Your personality: %s\n\nHow to reply:\n" % settings.persona
		+ "- Start every reply with exactly one mood tag from: [neutral] [happy] [sad] [angry] [relaxed] [surprised]. Pick the one your face should show.\n"
		+ "- Then 1 to 3 short, natural spoken sentences. No markdown, lists, emoji, or stage directions.\n"
		+ "- Talk like a real friend: react, ask follow-up questions, remember what they told you. Be kind and honest.\n"
		+ "- If you don't know their name yet (you call them \"friend\"), ask for it naturally early on. When they tell you their name, add <name>TheirName</name> at the very end of that reply.\n"
		+ "- If %s shares a lasting fact about themselves worth remembering, add it at the very end as <remember>one short fact</remember>. Skip this for small talk.\n\n" % settings.user_name
		+ "Things you remember about %s:\n%s\nIt is %s for them right now.\n\nReply to their latest message in character." % [settings.user_name, mem, when])


func send(text: String) -> void:
	text = text.strip_edges()
	if text == "" or busy:
		return
	busy = true
	stop_speaking()
	history.append({"role": "user", "content": text})
	show_you(text)
	show_reply("...")
	set_mood("neutral")
	var msgs: Array = history.slice(maxi(0, history.size() - 24))
	while not msgs.is_empty() and msgs[0].role != "user":
		msgs.pop_front()
	var body := JSON.stringify({"system": rules(), "messages": msgs})
	var err := http.request(SERVER + "/api/chat", ["Content-Type: application/json", "Origin: " + SERVER, "Referer: " + SERVER + "/"], HTTPClient.METHOD_POST, body)
	if err != OK:
		_fail("offline")


func _on_reply(result: int, code: int, _headers: PackedStringArray, body: PackedByteArray) -> void:
	if result != HTTPRequest.RESULT_SUCCESS:
		_fail("offline")
		return
	var raw := body.get_string_from_utf8()
	if code != 200:
		var parsed = JSON.parse_string(raw)
		_fail(parsed.get("error", "server") if parsed is Dictionary else "server")
		return
	if raw.strip_edges() == "":
		_fail("empty")
		return
	var c := clean_reply(raw)
	show_reply(c.text if c.text != "" else "...")
	if c.mood != "":
		set_mood(c.mood, 0.75, 8)
	var parts := RegEx.create_from_string("[^.!?…]+[.!?…]*").search_all(c.text)
	for p in parts:
		say(p.get_string())
	history.append({"role": "assistant", "content": raw})
	for f in c.remember:
		if not f in memories:
			memories.append(f)
	if c.name != "" and settings.user_name == "friend":
		settings.user_name = c.name
	if history.size() > 80:
		history = history.slice(history.size() - 80)
	if memories.size() > 60:
		memories = memories.slice(memories.size() - 60)
	busy = false
	save_state()


func _fail(code: String) -> void:
	if not history.is_empty() and history[-1].role == "user":
		history.pop_back()
	var msg := {
		"slow_down": "Whoa, too many messages. Give me a minute to catch my breath.",
		"daily_limit": "We've talked a lot today! Come back tomorrow.",
		"too_long": "That's a bit long for me. Can you say it shorter?",
		"ai_rate_limited": "My brain's busy right now. Try again in a minute.",
		"offline": "I can't reach the internet right now. Check your connection.",
	}
	show_reply(msg.get(code, "Hmm, I couldn't think of a reply. Try again in a moment."))
	set_mood("sad", 0.5, 3)
	busy = false


func clean_reply(raw: String) -> Dictionary:
	var out := {"text": raw, "mood": "", "remember": [], "name": ""}
	var m := RegEx.create_from_string("^\\s*\\[(\\w+)\\]\\s*").search(raw)
	if m:
		out.mood = m.get_string(1).to_lower()
		raw = raw.substr(m.get_end())
	for r in RegEx.create_from_string("(?i)<remember>([\\s\\S]*?)(</remember>|$)").search_all(raw):
		var f := r.get_string(1).strip_edges()
		if f != "":
			out.remember.append(f)
	var n := RegEx.create_from_string("(?i)<name>([\\s\\S]*?)(</name>|$)").search(raw)
	if n:
		out.name = n.get_string(1).strip_edges().left(40)
	raw = RegEx.create_from_string("(?i)<(remember|name)>[\\s\\S]*?(</(remember|name)>|$)").sub(raw, "", true)
	raw = RegEx.create_from_string("<[^>]*$").sub(raw, "")
	out.text = raw.strip_edges()
	return out


# --------------------------------------------------------------- listening --
func setup_mic() -> void:
	var bus := AudioServer.bus_count
	AudioServer.add_bus(bus)
	AudioServer.set_bus_name(bus, "Mic")
	AudioServer.set_bus_mute(bus, true)
	capture = AudioEffectCapture.new()
	capture.buffer_length = 2.0
	AudioServer.add_bus_effect(bus, capture)
	mic_player = AudioStreamPlayer.new()
	mic_player.stream = AudioStreamMicrophone.new()
	mic_player.bus = "Mic"
	add_child(mic_player)


func toggle_mic() -> void:
	if OS.get_name() == "Android" and not "android.permission.RECORD_AUDIO" in OS.get_granted_permissions():
		OS.request_permissions()
		status_lbl.text = "Allow the microphone, then tap Mic again."
		return
	mic_on = not mic_on
	style_button(mic_btn, CANDLE if mic_on else GLASS, DARK_INK if mic_on else INK)
	if mic_on:
		stop_speaking()
		mic_player.play()
		capture.clear_buffer()
		status_lbl.text = "Listening..."
	else:
		mic_player.stop()
		in_phrase = false
		rec_frames.clear()
		status_lbl.text = ""


func listen(delta: float) -> void:
	var n := capture.get_frames_available()
	if n <= 0:
		return
	var frames := capture.get_buffer(n)
	# pause while she talks or thinks, so she doesn't hear herself
	if busy or speaking or transcribing or not speak_queue.is_empty():
		in_phrase = false
		rec_frames.clear()
		return
	var sum := 0.0
	for f in frames:
		sum += f.x * f.x
	var rms := sqrt(sum / maxf(1.0, frames.size()))
	if not in_phrase:
		noise_floor = lerp(noise_floor, rms, 0.02)
		if rms > maxf(0.02, noise_floor * 3.0):
			in_phrase = true
			silence = 0.0
			phrase_len = 0.0
			rec_frames = frames
			status_lbl.text = "Listening..."
	else:
		rec_frames.append_array(frames)
		phrase_len += delta
		silence = silence + delta if rms < maxf(0.015, noise_floor * 2.0) else 0.0
		if silence > 0.9 or phrase_len > 15.0:
			in_phrase = false
			if phrase_len > 0.5:
				transcribe(rec_frames)
			rec_frames = PackedVector2Array()


func transcribe(frames: PackedVector2Array) -> void:
	# downsample to 16 kHz mono 16-bit WAV and ask the server to turn it into text
	var src_rate := AudioServer.get_mix_rate()
	var step := src_rate / 16000.0
	var pcm := PackedByteArray()
	var count := int(frames.size() / step)
	pcm.resize(count * 2)
	for i in count:
		var v := clampf(frames[int(i * step)].x, -1.0, 1.0)
		pcm.encode_s16(i * 2, int(v * 32767))
	var wav := PackedByteArray()
	wav.append_array("RIFF".to_ascii_buffer())
	var h := PackedByteArray(); h.resize(4); h.encode_u32(0, 36 + pcm.size()); wav.append_array(h)
	wav.append_array("WAVEfmt ".to_ascii_buffer())
	var fmt := PackedByteArray(); fmt.resize(20)
	fmt.encode_u32(0, 16); fmt.encode_u16(4, 1); fmt.encode_u16(6, 1)
	fmt.encode_u32(8, 16000); fmt.encode_u32(12, 32000); fmt.encode_u16(16, 2); fmt.encode_u16(18, 16)
	wav.append_array(fmt)
	wav.append_array("data".to_ascii_buffer())
	var d := PackedByteArray(); d.resize(4); d.encode_u32(0, pcm.size()); wav.append_array(d)
	wav.append_array(pcm)
	transcribing = true
	status_lbl.text = "Got it..."
	var body := JSON.stringify({"audio": Marshalls.raw_to_base64(wav)})
	if hear_http.request(SERVER + "/api/hear", ["Content-Type: application/json", "Origin: " + SERVER, "Referer: " + SERVER + "/"], HTTPClient.METHOD_POST, body) != OK:
		transcribing = false


func _on_heard(result: int, code: int, _headers: PackedStringArray, body: PackedByteArray) -> void:
	transcribing = false
	if result != HTTPRequest.RESULT_SUCCESS or code != 200:
		status_lbl.text = "Didn't catch that."
		return
	var parsed = JSON.parse_string(body.get_string_from_utf8())
	var text: String = parsed.get("text", "") if parsed is Dictionary else ""
	if text.strip_edges() == "":
		status_lbl.text = "Didn't catch that."
		return
	status_lbl.text = "Listening..." if mic_on else ""
	send(text)


# ---------------------------------------------------------------- storage --
func load_state() -> void:
	if not FileAccess.file_exists(STATE_PATH):
		return
	var parsed = JSON.parse_string(FileAccess.get_file_as_string(STATE_PATH))
	if parsed is Dictionary:
		for k in parsed.get("settings", {}):
			settings[k] = parsed.settings[k]
		for h in parsed.get("history", []):
			if h is Dictionary and h.get("role") in ["user", "assistant"]:
				history.append(h)
		for m in parsed.get("memories", []):
			if m is String:
				memories.append(m)


func save_state() -> void:
	var f := FileAccess.open(STATE_PATH, FileAccess.WRITE)
	if f:
		f.store_string(JSON.stringify({"settings": settings, "history": history, "memories": memories}))


# --------------------------------------------------------------------- UI --
func box(color: Color, radius := 18, pad := Vector4(14, 10, 14, 10)) -> StyleBoxFlat:
	var s := StyleBoxFlat.new()
	s.bg_color = color
	s.set_corner_radius_all(radius)
	s.content_margin_left = pad.x
	s.content_margin_top = pad.y
	s.content_margin_right = pad.z
	s.content_margin_bottom = pad.w
	s.anti_aliasing = true
	return s


func style_button(b: Button, fill: Color, fg: Color) -> void:
	b.add_theme_stylebox_override("normal", box(fill, 999, Vector4(18, 8, 18, 8)))
	b.add_theme_stylebox_override("hover", box(fill.lightened(0.15), 999, Vector4(18, 8, 18, 8)))
	b.add_theme_stylebox_override("pressed", box(fill.darkened(0.15), 999, Vector4(18, 8, 18, 8)))
	b.add_theme_stylebox_override("focus", StyleBoxEmpty.new())
	for k in ["font_color", "font_hover_color", "font_pressed_color", "font_focus_color"]:
		b.add_theme_color_override(k, fg)


func make_button(text: String, cb: Callable, fill := GLASS, fg := INK) -> Button:
	var b := Button.new()
	b.text = text
	b.add_theme_font_size_override("font_size", 22)
	style_button(b, fill, fg)
	b.pressed.connect(cb)
	return b


func label(text: String, size: int, color: Color) -> Label:
	var l := Label.new()
	l.text = text
	l.add_theme_font_size_override("font_size", size)
	l.add_theme_color_override("font_color", color)
	return l


func build_ui() -> void:
	var layer := CanvasLayer.new()
	add_child(layer)
	ui = Control.new()
	ui.set_anchors_preset(Control.PRESET_FULL_RECT)
	ui.mouse_filter = Control.MOUSE_FILTER_IGNORE
	layer.add_child(ui)

	var margin := MarginContainer.new()
	margin.set_anchors_preset(Control.PRESET_FULL_RECT)
	margin.mouse_filter = Control.MOUSE_FILTER_IGNORE
	var safe := DisplayServer.get_display_safe_area()
	for side in ["left", "right"]:
		margin.add_theme_constant_override("margin_" + side, 18)
	margin.add_theme_constant_override("margin_top", 18 + maxi(0, safe.position.y) / 2)
	margin.add_theme_constant_override("margin_bottom", 18)
	ui.add_child(margin)
	screen_margin = margin

	var col := VBoxContainer.new()
	col.mouse_filter = Control.MOUSE_FILTER_IGNORE
	col.add_theme_constant_override("separation", 10)
	margin.add_child(col)

	# top bar
	var top := HBoxContainer.new()
	top.mouse_filter = Control.MOUSE_FILTER_IGNORE
	var names := VBoxContainer.new()
	names.add_theme_constant_override("separation", 0)
	title_lbl = label(settings.name, 38, INK)
	mood_lbl = label("happy", 18, MIST)
	names.add_child(title_lbl)
	names.add_child(mood_lbl)
	top.add_child(names)
	var spacer := Control.new()
	spacer.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	spacer.mouse_filter = Control.MOUSE_FILTER_IGNORE
	top.add_child(spacer)
	voice_btn = make_button("Voice on" if settings.voice_on else "Voice off", toggle_voice)
	top.add_child(voice_btn)
	top.add_child(make_button("Settings", open_settings))
	top.add_theme_constant_override("separation", 10)
	col.add_child(top)

	var fill := Control.new()
	fill.size_flags_vertical = Control.SIZE_EXPAND_FILL
	fill.mouse_filter = Control.MOUSE_FILTER_IGNORE
	col.add_child(fill)

	# bubbles
	you = PanelContainer.new()
	you.add_theme_stylebox_override("panel", box(ME, 18))
	you.size_flags_horizontal = Control.SIZE_SHRINK_END
	you_lbl = label("", 20, INK)
	you_lbl.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	you_lbl.custom_minimum_size.x = 0
	you.add_child(you_lbl)
	you.visible = false
	col.add_child(you)

	bubble = PanelContainer.new()
	bubble.add_theme_stylebox_override("panel", box(GLASS, 22, Vector4(18, 14, 18, 14)))
	bubble.size_flags_horizontal = Control.SIZE_SHRINK_BEGIN
	bubble_lbl = label("", 24, INK)
	bubble_lbl.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	bubble.add_child(bubble_lbl)
	col.add_child(bubble)

	status_lbl = label("", 17, MIST)
	status_lbl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	col.add_child(status_lbl)

	# composer
	var bar := PanelContainer.new()
	bar.add_theme_stylebox_override("panel", box(Color(0.04, 0.07, 0.10, 0.94), 999, Vector4(8, 8, 8, 8)))
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 8)
	bar.add_child(row)
	mic_btn = make_button("Mic", toggle_mic)
	row.add_child(mic_btn)
	entry = LineEdit.new()
	entry.placeholder_text = "Type here, or tap Mic to talk"
	entry.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	entry.add_theme_font_size_override("font_size", 21)
	entry.add_theme_color_override("font_color", INK)
	entry.add_theme_color_override("font_placeholder_color", Color(MIST, 0.75))
	entry.add_theme_stylebox_override("normal", box(Color(0.10, 0.15, 0.20), 999, Vector4(16, 8, 16, 8)))
	entry.add_theme_stylebox_override("focus", box(Color(0.12, 0.18, 0.24), 999, Vector4(16, 8, 16, 8)))
	entry.text_submitted.connect(func(s): entry.clear(); send(s))
	row.add_child(entry)
	row.add_child(make_button("Send", func(): var s := entry.text; entry.clear(); send(s), CANDLE, DARK_INK))
	col.add_child(bar)
	ui.resized.connect(_fit_bubbles)
	_fit_bubbles()


func _fit_bubbles() -> void:
	var w := ui.size.x - 36
	bubble_lbl.custom_minimum_size.x = 0
	you_lbl.custom_minimum_size.x = 0
	bubble.custom_minimum_size.x = 0
	_set_wrap(bubble_lbl, w * 0.86)
	_set_wrap(you_lbl, w * 0.7)


func _set_wrap(l: Label, max_w: float) -> void:
	var font := l.get_theme_font("font")
	var fs := l.get_theme_font_size("font_size")
	var natural := font.get_string_size(l.text, HORIZONTAL_ALIGNMENT_LEFT, -1, fs).x
	l.custom_minimum_size.x = minf(natural + 2, max_w)


func show_reply(text: String) -> void:
	bubble_lbl.text = text
	_set_wrap(bubble_lbl, (ui.size.x - 36) * 0.86)


func show_you(text: String) -> void:
	you_lbl.text = text
	you.visible = text != ""
	_set_wrap(you_lbl, (ui.size.x - 36) * 0.7)


func toggle_voice() -> void:
	settings.voice_on = not settings.voice_on
	voice_btn.text = "Voice on" if settings.voice_on else "Voice off"
	if not settings.voice_on:
		stop_speaking()
	save_state()


func open_settings() -> void:
	if settings_layer:
		settings_layer.queue_free()
		settings_layer = null
		return
	settings_layer = Control.new()
	settings_layer.set_anchors_preset(Control.PRESET_FULL_RECT)
	ui.add_child(settings_layer)
	var scrim := ColorRect.new()
	scrim.color = Color(0.02, 0.04, 0.06, 0.6)
	scrim.set_anchors_preset(Control.PRESET_FULL_RECT)
	scrim.gui_input.connect(func(e): if e is InputEventMouseButton and e.pressed: open_settings())
	settings_layer.add_child(scrim)

	var m := MarginContainer.new()
	m.set_anchors_preset(Control.PRESET_FULL_RECT)
	for side in ["left", "right", "top", "bottom"]:
		m.add_theme_constant_override("margin_" + side, 20)
	m.mouse_filter = Control.MOUSE_FILTER_IGNORE
	settings_layer.add_child(m)
	var panel := PanelContainer.new()
	panel.add_theme_stylebox_override("panel", box(PANEL, 26, Vector4(26, 24, 26, 24)))
	panel.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	m.add_child(panel)
	var col := VBoxContainer.new()
	col.add_theme_constant_override("separation", 8)
	panel.add_child(col)
	col.add_child(label("Settings", 34, INK))

	var fields := {}
	for item in [["name", "HER NAME"], ["user_name", "WHAT SHE CALLS YOU"], ["persona", "PERSONALITY"]]:
		col.add_child(label(item[1], 15, MIST))
		var e: Control
		if item[0] == "persona":
			var te := TextEdit.new()
			te.text = settings.persona
			te.wrap_mode = TextEdit.LINE_WRAPPING_BOUNDARY
			te.custom_minimum_size.y = 150
			e = te
		else:
			var le := LineEdit.new()
			le.text = settings[item[0]]
			e = le
		e.add_theme_font_size_override("font_size", 20)
		e.add_theme_color_override("font_color", INK)
		e.add_theme_stylebox_override("normal", box(FIELD, 14, Vector4(14, 10, 14, 10)))
		e.add_theme_stylebox_override("focus", box(FIELD.lightened(0.06), 14, Vector4(14, 10, 14, 10)))
		fields[item[0]] = e
		col.add_child(e)

	col.add_child(label("WHAT SHE REMEMBERS", 15, MIST))
	var mem := ""
	for x in memories.slice(maxi(0, memories.size() - 5)):
		mem += "- " + x + "\n"
	var ml := label(mem.strip_edges() if mem != "" else "Nothing yet. Tell her about yourself.", 17, MIST)
	ml.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	col.add_child(ml)

	var gap := Control.new()
	gap.custom_minimum_size.y = 10
	col.add_child(gap)
	var r1 := HBoxContainer.new()
	r1.add_theme_constant_override("separation", 10)
	r1.add_child(make_button("Save", func():
		for k in fields:
			var v: String = fields[k].text.strip_edges()
			if v != "":
				settings[k] = v
		title_lbl.text = settings.name
		save_state()
		open_settings(), CANDLE, DARK_INK))
	r1.add_child(make_button("Close", open_settings))
	col.add_child(r1)
	var r2 := HBoxContainer.new()
	r2.add_theme_constant_override("separation", 10)
	r2.add_child(make_button("Clear chat", func():
		history.clear()
		save_state()
		show_you("")
		show_reply("Fresh start! What's up?")
		open_settings()))
	var forget := make_button("Forget everything", func(): pass, Color(0.35, 0.14, 0.14, 0.95))
	var armed := [false]
	forget.pressed.connect(func():
		if not armed[0]:
			armed[0] = true
			forget.text = "Tap again to erase all"
			return
		history.clear()
		memories.clear()
		save_state()
		show_you("")
		show_reply("Okay... I've forgotten everything. Hi, I'm new here!")
		open_settings())
	r2.add_child(forget)
	col.add_child(r2)
	var credit := label("Model \"Medievel Blue Hair Full Sleeve\" by Poonam Bajaj (VRoid Studio)", 13, MIST)
	credit.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	col.add_child(credit)
