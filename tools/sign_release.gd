## Signs a Holomem Flicker release manifest with the owner's offline key (same key as lofi-room).
##   godot --headless -s tools/sign_release.gd -- <private key .pem> <release.json> [update_key.pub]
## Writes <release.json>.sig (base64 RSA PKCS#1 v1.5 over SHA-256 of the file) and checks it against
## the public key that ships in the app. Then commit release.json + release.json.sig into update/.
extends SceneTree

func _init() -> void:
	var a := OS.get_cmdline_user_args()
	if a.size() < 2:
		push_error("usage: godot --headless -s tools/sign_release.gd -- <key.pem> <release.json> [update_key.pub]")
		quit(1)
		return
	var key := CryptoKey.new()
	if key.load(a[0], false) != OK:
		push_error("could not read the private key " + a[0])
		quit(1)
		return
	var body := FileAccess.get_file_as_bytes(a[1])
	if body.is_empty():
		push_error("could not read " + a[1])
		quit(1)
		return
	var h := HashingContext.new()
	h.start(HashingContext.HASH_SHA256)
	h.update(body)
	var digest := h.finish()
	var sig := Crypto.new().sign(HashingContext.HASH_SHA256, digest, key)
	var out := FileAccess.open(a[1] + ".sig", FileAccess.WRITE)
	out.store_string(Marshalls.raw_to_base64(sig) + "\n")
	out.close()
	var pub_path := a[2] if a.size() > 2 else a[1].get_base_dir().path_join("../tools/update_key.pub")
	var pub := CryptoKey.new()
	if pub.load(pub_path, true) != OK:
		push_warning("signed, but couldn't load the public key to double-check: " + pub_path)
		quit()
		return
	if not Crypto.new().verify(HashingContext.HASH_SHA256, digest, sig, pub):
		push_error("signature does NOT verify with " + pub_path + " - wrong key?")
		quit(1)
		return
	print("signed ", a[1], " -> ", a[1], ".sig (verified)")
	quit()
