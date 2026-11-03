# xnotes-share companion (Windows/macOS/Linux-ready)

Stdlib-only Python 3 client for the xnotes LAN share server. No `pip`
dependencies — it runs on stock lab machines, and the same tree runs on
Windows (`py xnotes_share.py ...`) and macOS.

```bash
python3 xnotes_share.py pair --url https://192.168.1.20:42317 \
    --fingerprint AB:CD:... --token <token-from-device>
python3 xnotes_share.py list [folder-uri]   # browse; subfolders via their uri
python3 xnotes_share.py gui     # file browser with search + per-file Draw buttons
```

Per-file workflow (URIs come from `list`; quote them):

```bash
python3 xnotes_share.py pdf '<uri>' note.pdf   # snapshot for an external viewer
python3 xnotes_share.py push '<uri>' note.pdf  # lock + version-checked upload
python3 xnotes_share.py watch '<uri>' --download-to note.pdf  # follow device commits
python3 xnotes_share.py canvas '<uri>'         # built-in drawing canvas (live strokes)
```

`python3 xnotes_share.py selftest` runs offline checks without a device.
See `docs/share-sync.md` for the protocol, the security model, USB setup,
the policy screen, and the canvas sync design.
