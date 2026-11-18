# Search scripts

These are the scripts behind [docs/RESEARCH.md](../docs/RESEARCH.md). They are Python with CUDA kernels inside (through CuPy), so you need an NVIDIA GPU. The glyph sweep also uses `msvcrt` for its lock file, so as written it runs on Windows.

| File | What it does |
|---|---|
| `planted_gpu.py` | The GPU kernel code that plays a bone meal tree out from a starting state. The other two import it. |
| `glyph_sweep.py` | Sweeps every state that passes the 40% roll and keeps the best tree for each glyph. Stops cleanly and resumes from its checkpoint. |
| `sibling_gpu.py` | Grows each state as warped and crimson and logs the pairs that match. Also resumable. |
| `out/glyph_targets.json` | The font: 5x5 pictures of every character. |

## Running them

Install CuPy for your CUDA version, then from this folder:

```
python glyph_sweep.py
python sibling_gpu.py --minutes 30
```

Results and the checkpoint go into `out/`. To stop the glyph sweep, create an empty file called `out/glyph_STOP`. Delete it and run the script again to carry on.

`glyph_sweep.py` on a decent card does just over a billion trees a second, so a full pass takes a bit over a day. `sibling_gpu.py` has a `--bench 20` option that runs a 20 second speed test without writing anything.

The `--validate` option of `sibling_gpu.py` compares against a separate reference implementation that isn't included here, so it won't work from this repo.

The font in `glyph_targets.json` is plain data. Put `#` for a shroomlight and `.` for nothing, and the centre cell is where the trunk goes, so it counts as a wildcard. Changing it changes what the sweep looks for, and the sweep refuses to resume an old checkpoint if the targets changed.
