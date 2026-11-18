"""Resumable detached GPU sweep for the OVERNIGHT glyph search (planted WARPED trees, canvas = layer start+3 of hh>8 trees).

  python.exe -I glyph_sweep.py [--ckpt-seconds 30] [--batch-log2 26] [--sleep 0]

Targets: out/glyph_targets.json.  Every glyph is expanded into all world masks (4 headings x translations inside the 5x5
canvas, or only the painted position if `lock`; centre cell = stem wildcard, so it is dropped from the mask).  Glyphs with the
same mask set (rotations of each other, e.g. M/W, i/!) share one "shape".
Kernel: simulates the tree up to the canvas layer; if the layer's shroomlight set equals a table mask (ring clean) it
  * counts a canvas hit,
  * continues the simulation up to the top and rejects the tree when a shroomlight would be visible from below through an
    air cell of the canvas (so every logged hit is exactly "view from below shows the glyph"),
  * score = stemQuality << 14 | trunkClear << 9 | weightedBackdrop  (trunkClear = 20 - 3*face - 2*diagonal stroke cells around the trunk) (4 - face neighbours of the stem that are strokes) (stem-free > stem in a straight run > junction > endpoint/bend; backdrop wart
    next to a stroke counts 7, other wart 2; wart one layer up behind an air canvas cell 3 / 1); the SPACE pseudo-mask (empty canvas,
    no sky-open column) scores 4<<11 | 4<<8 | wart.
    keeps per mask atomicMax of (score << 48 | (MASK48 - s1))  (best score, earliest s1 on ties).
    After the first pass reaches S1_LIMIT the sweep WRAPS to re-sweep [0, wrap_end) once (those trees had the old scoring).
State: out/glyph_checkpoint.json (next_s1, per-mask best/hits), out/glyph_best.json (per shape), out/glyph_progress.log,
out/glyph_sweep.lock, stop with out/glyph_STOP.  Rerun to resume.
"""
import os, sys, time, json, argparse, hashlib, msvcrt
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
OUT = os.path.join(HERE, "out")
TBITS = 14
MASK48 = (1 << 48) - 1
KEYBITS = 47                 # key = score << 47 | (MASK47 - s1); s1 < 6710887 * 2^24 < 2^47
MASK47 = (1 << KEYBITS) - 1
HEAD = {"north": (0, -1), "east": (1, 0), "south": (0, 1), "west": (-1, 0)}
CENTER_BIT = 1 << ((0 + 3) * 7 + (0 + 3))
INTERIOR24 = 0
for _dx in range(-2, 3):
    for _dz in range(-2, 3):
        if (_dx, _dz) != (0, 0):
            INTERIOR24 |= 1 << ((_dx + 3) * 7 + _dz + 3)


def world_cell(i, j, head):
    """screen cell (row i, col j) as read when looking straight up after facing `head` -> world (dx, dz).
    Top of the screen points to -F (behind you), right of the screen is (uz,-ux) (see alphabet_post.py)."""
    fx, fz = HEAD[head]
    ux, uz = -fx, -fz
    rx, rz = uz, -ux
    return ux * (2 - i) + rx * (j - 2), uz * (2 - i) + rz * (j - 2)


def bit(dx, dz):
    return 1 << ((dx + 3) * 7 + dz + 3)

RING24 = 0
for _x in range(-3, 4):
    for _z in range(-3, 4):
        if max(abs(_x), abs(_z)) == 3:
            RING24 |= bit(_x, _z)


FACE4 = bit(1, 0) | bit(-1, 0) | bit(0, 1) | bit(0, -1)
DIAG4 = bit(1, 1) | bit(1, -1) | bit(-1, 1) | bit(-1, -1)


def trunk_clear(mask):
    """20 - (3 per stroke cell directly beside the trunk + 2 per diagonal one): 20 = nothing touches the trunk"""
    return 20 - 3 * bin(mask & FACE4).count("1") - 2 * bin(mask & DIAG4).count("1")


SPACEKEY = 1 << 62      # pseudo-mask of the SPACE glyph (empty canvas)


def is_space(g):
    return not any(ch == "#" for row in g["rows"] for ch in row)


def stroke_cells(g, head, sft):
    return set(world_cell(r + sft[0], c + sft[1], head) for r, row in enumerate(g["rows"]) for c, ch in enumerate(row) if ch == "#")


def stem_quality(cells):
    """cells: set of world (dx,dz) stroke cells (centre included if the glyph paints it).  4 gap, 2 straight-run middle, 1 junction, 0 endpoint/bend/dot"""
    if (0, 0) not in cells:
        return 4
    nb = [(a, b) for a in (-1, 0, 1) for b in (-1, 0, 1) if (a, b) != (0, 0) and (a, b) in cells]
    if len(nb) >= 3:
        return 1
    if len(nb) == 2 and nb[0][0] + nb[1][0] == 0 and nb[0][1] + nb[1][1] == 0:
        return 2
    return 0


def adj_mask(cells, mask):
    """interior cells (not stroke, not centre) 8-adjacent to a stroke cell"""
    m = 0
    for (x, z) in cells:
        for a in (-1, 0, 1):
            for b in (-1, 0, 1):
                if (a or b) and abs(x + a) <= 3 and abs(z + b) <= 3:
                    m |= bit(x + a, z + b)
    return m & (INTERIOR24 | RING24) & ~mask


def weighted_backdrop(wartmask, adj, upmask=0):
    """backdrop score, same as the kernel (wartmask covers the 5x5 area and the 7x7 outer ring; upmask the 5x5 area only)"""
    c = lambda m: bin(m & (INTERIOR24 | RING24)).count("1")
    i = lambda m: bin(m & INTERIOR24).count("1")
    # 5x5 glyph area: 7 next to a stroke / 2 elsewhere; outer ring: 7 next to a stroke / 0 elsewhere;
    # layer above (5x5 area only, behind an air cell): 3 next to a stroke / 1 elsewhere
    return 7 * c(wartmask & adj) + 2 * i(wartmask & ~adj) + 3 * i(upmask & adj) + i(upmask & ~adj)


def shape_quality(g_list):
    """(mask -> (stemQuality, adj)) for the glyphs of ONE shape"""
    return mask_quality(g_list)


def mask_quality(glyphs):
    """mask -> (stemQuality, adjacency mask) : best (max stemQuality) variant over all glyph variants that give this mask"""
    q = {}
    for g in glyphs:
        if is_space(g):
            q[SPACEKEY] = (4, 0); continue
        for head, sft, m in glyph_variants(g):
            if not m:
                continue
            cells = stroke_cells(g, head, sft)
            cand = (stem_quality(cells), adj_mask(cells, m))
            if m not in q or cand[0] > q[m][0]:
                q[m] = cand
    return q


def glyph_variants(g):
    """all (head, (dr,dc), mask) placements of glyph g (dict with rows, lock) ; mask excludes the centre cell"""
    strokes = [(r, c) for r, row in enumerate(g["rows"]) for c, ch in enumerate(row) if ch == "#"]
    if not strokes:
        return [("north", (0, 0), SPACEKEY)]
    rs = [r for r, c in strokes]; cs = [c for r, c in strokes]
    shifts = [(0, 0)] if g.get("lock") else [(dr, dc) for dr in range(-min(rs), 5 - max(rs)) for dc in range(-min(cs), 5 - max(cs))]
    out = []
    for head in HEAD:
        for dr, dc in shifts:
            if (2 - dr, 2 - dc) in strokes and _looks_like_other(g, strokes, dr, dc):
                continue      # the stem hides a stroke cell and what is left reads as another character (8 -> O)
            m = 0
            for r, c in strokes:
                dx, dz = world_cell(r + dr, c + dc, head)
                m |= bit(dx, dz)
            m &= ~CENTER_BIT
            out.append((head, (dr, dc), m))
    return out


def _shape(cells):
    if not cells:
        return frozenset()
    a, b = min(r for r, c in cells), min(c for r, c in cells)
    return frozenset((r - a, c - b) for r, c in cells)


_LOOKS = {}
STEM_CONFUSABLE = {"8": "O0", "O": "8"}   # only these pairs: a placement whose stem hides a cell and leaves the other is skipped


def _looks_like_other(g, strokes, dr, dc):
    """True if the glyph placed at (dr, dc), with its centre cell covered by the stem, has exactly the visible shape of a
    different character's full strokes."""
    if not _LOOKS:
        for t in load_targets():
            st = [(r, c) for r, row in enumerate(t["rows"]) for c, ch in enumerate(row) if ch == "#"]
            if not st:
                continue
            _LOOKS.setdefault(t["ch"], set()).add(_shape(st))
    other = STEM_CONFUSABLE.get(g["ch"])
    if not other:
        return False
    vis = _shape([(r + dr, c + dc) for r, c in strokes if (r + dr, c + dc) != (2, 2)])
    return any(vis in _LOOKS.get(ch, ()) for ch in other)


def load_targets():
    return json.load(open(os.path.join(OUT, "glyph_targets.json")))["glyphs"]


def build_shapes(glyphs=None):
    """returns (shapes, masks)  shapes: list of {key, ids, masks(sorted list)}, masks: sorted list of distinct masks"""
    glyphs = glyphs or load_targets()
    by_key = {}
    for g in glyphs:
        ms = frozenset(m for _, _, m in glyph_variants(g) if m)
        by_key.setdefault(ms, []).append(g["id"])
    shapes = [{"ids": ids, "masks": sorted(ms)} for ms, ids in by_key.items()]
    shapes.sort(key=lambda s: s["ids"][0])
    allm = sorted(set(m for s in shapes for m in s["masks"]))
    return shapes, allm


def table_array(keys):
    M = 1 << TBITS
    arr = np.zeros(M, dtype=np.uint64)
    slot_of = {}
    maxprobe = 0
    for key in keys:
        h = ((key * 0x9E3779B97F4A7C15) & ((1 << 64) - 1)) >> (64 - TBITS)
        p = 0
        while arr[h] != 0:
            h = (h + 1) & (M - 1); p += 1
        arr[h] = key; slot_of[key] = h; maxprobe = max(maxprobe, p)
    assert maxprobe < 60, maxprobe
    return arr, slot_of


SRC_BODY = r"""
__device__ __forceinline__ int lookup(const u64 *TAB, u64 key) {
    u32 h = (u32)((key * 0x9E3779B97F4A7C15ULL) >> (64 - TBITS));
    for (int i = 0; i < 64; i++) {
        u64 t = TAB[h];
        if (t == key) return (int)h;
        if (t == 0) return -1;
        h = (h + 1) & ((1u << TBITS) - 1);
    }
    return -1;
}

extern "C" __global__ void glyph(u64 start, u64 count, const u64 *TAB, const u32 *SQ, const u64 *ADJ, const int *ALT, u64 *best, u32 *cnt, u32 *cntV, u64 *hhCount) {
    u64 nthreads = (u64)gridDim.x * blockDim.x;
    u64 tid = (u64)blockIdx.x * blockDim.x + threadIdx.x;
    u32 localhh = 0;
    for (u64 idx = tid; idx < count; idx += nthreads) {
        u64 s1 = start + idx;
        u64 s = s1;
        int H = nextInt(s, 10) + 4;
        if (nextInt(s, 12) == 0) H *= 2;
        int hh = nextInt(s, 1 + H / 3) + 5;
        if (hh > H) hh = H;
        if (hh <= 8) continue;
        localhh++;
        int start_ = H - hh;
        u64 prevMask = 0, sm = 0;
        for (int dy = start_; dy <= start_ + 3; dy++) {
            int rr = nextInt(s, 3);
            int radius = 3;
            bool bottom = dy < start_ + 3;
            u64 curMask = 0; sm = 0;
            for (int dx = -radius; dx <= radius; dx++) {
                bool ex = (dx == -radius) || (dx == radius);
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    bool ez = (dz == -radius) || (dz == radius);
                    bool inside = !ex && !ez;
                    bool corner = ex && ez;
                    int id = (dx + 3) * 7 + (dz + 3);
                    if (bottom) {
                        if (inside) continue;
                        if ((prevMask >> id) & 1ULL) { curMask |= 1ULL << id; }
                        else if (nxt(s, 24) < THR_DROP) { curMask |= 1ULL << id; }
                    } else {
                        u32 tD, tH;
                        if (inside) { tD = THR_D_IN; tH = THR_H_IN; }
                        else if (corner) { tD = THR_D_CO; tH = THR_H_CO; }
                        else { tD = THR_D_ED; tH = THR_H_ED; }
                        if (nxt(s, 24) < tD) { curMask |= 1ULL << id; sm |= 1ULL << id; }
                        else if (nxt(s, 24) < tH) { curMask |= 1ULL << id; nxt(s, 24); }
                    }
                }
            }
            prevMask = curMask;
        }
        int slot = lookup(TAB, sm ? sm : SPACEKEY_);
        if (slot < 0) continue;
        for (int sl = slot; sl >= 0; sl = ALT[sl]) atomicAdd(&cnt[sl], 1u);
        // upper layers: a shroomlight seen from below through an air cell of the canvas spoils the glyph
        u64 canv = prevMask, covered = 0, above = 0;   // above: wart in the layer right above the canvas
        bool bad = false;
        for (int dy = start_ + 4; dy <= H && !bad; dy++) {
            int rr = nextInt(s, 3);
            int radius = dy < H - rr ? 2 : 1;
            for (int dx = -radius; dx <= radius && !bad; dx++) {
                bool ex = (dx == -radius) || (dx == radius);
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dz == 0 && dy < H) continue;
                    bool ez = (dz == -radius) || (dz == radius);
                    bool inside = !ex && !ez && dy != H;
                    bool corner = ex && ez;
                    int id = (dx + 3) * 7 + (dz + 3);
                    u32 tD, tH;
                    if (inside) { tD = THR_D_IN; tH = THR_H_IN; }
                    else if (corner) { tD = THR_D_CO; tH = THR_H_CO; }
                    else { tD = THR_D_ED; tH = THR_H_ED; }
                    bool isS = false, isW = false;
                    if (nxt(s, 24) < tD) isS = true;
                    else if (nxt(s, 24) < tH) { isW = true; nxt(s, 24); }
                    if (isW && dy == start_ + 4) above |= 1ULL << id;
                    if ((isS || isW) && !(dx == 0 && dz == 0) && !((canv >> id) & 1ULL) && !((covered >> id) & 1ULL)) {
                        if (isS) { bad = true; break; }
                        covered |= 1ULL << id;
                    }
                }
            }
        }
        if (bad) continue;
        if (!sm) {
            // SPACE: no air canvas column may be open to the sky (some hat block above every air cell)
            if (((canv | covered) & INTERIOR24) != INTERIOR24) continue;
            u64 score = (4ULL << 14) | (20ULL << 9) | (u64)__popcll(canv & INTERIOR24);
            atomicAdd(&cntV[slot], 1u);
            u64 key = (score << KEYBITS_) | (MASKKv - s1);
            if (key > best[slot]) atomicMax(&best[slot], key);
        } else {
            u64 wart = canv & ~sm & (INTERIOR24 | RING24);   // the outer ring frames edge strokes too
            u64 up = above & ~canv & INTERIOR24;          // air canvas cell with wart one layer higher behind it
            for (int sl = slot; sl >= 0; sl = ALT[sl]) {
                u64 a = ADJ[sl];
                // backdrop: 5x5 glyph area wart 7 next to a stroke / 2 elsewhere; outer ring wart 7 next to a stroke / 0 elsewhere;
                // wart one layer up behind an air cell of the 5x5 area 3 next to a stroke / 1 elsewhere
                u64 bd = 7 * __popcll(wart & a) + 2 * __popcll(wart & ~a & INTERIOR24) + 3 * __popcll(up & a) + __popcll(up & ~a);
                u64 trunk = 20 - 3 * __popcll(sm & FACE4) - 2 * __popcll(sm & DIAG4);   // diagonal contact = 2/3 of a face contact
                u64 score = ((u64)SQ[sl] << 14) | (trunk << 9) | bd;
                atomicAdd(&cntV[sl], 1u);
                u64 key = (score << KEYBITS_) | (MASKKv - s1);
                if (key > best[sl]) atomicMax(&best[sl], key);
            }
        }
    }
    if (localhh) atomicAdd(hhCount, (u64)localhh);
}
"""


def build_source():
    import planted_gpu as pg
    d = pg.defines(False)
    src = "".join("#define %s %du\n" % (k_, v) if k_.startswith("THR") else "#define %s %d\n" % (k_, v) for k_, v in d.items())
    k = pg.KERNEL
    prefix = k[:k.index("struct Res")]
    src += "#define SPACEKEY_ %dULL\n#define TBITS %d\n#define KEYBITS_ %d\n#define MASKKv %dULL\n#define INTERIOR24 %dULL\n#define RING24 %dULL\n#define FACE4 %dULL\n#define DIAG4 %dULL\n" % (SPACEKEY, TBITS, KEYBITS, MASK47, INTERIOR24, RING24, FACE4, DIAG4)
    return src + prefix + SRC_BODY


def atomic_write(path, text):
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        f.write(text); f.flush(); os.fsync(f.fileno())
    os.replace(tmp, path)


LOGNAME = "glyph_progress.log"


def log(msg):
    line = time.strftime("%Y-%m-%d %H:%M:%S ") + msg
    with open(os.path.join(OUT, LOGNAME), "a") as f:
        f.write(line + "\n")
    print(line, flush=True)


def decode(key):
    if not key:
        return None
    return {"score": key >> KEYBITS, "s1": MASK47 - (key & MASK47)}


def write_best(ck, shapes, glyphs_by_id, tag=""):
    import planted_gpu as pg
    res = []
    for si, s in enumerate(shapes):
        bk, hits, vh = 0, 0, 0
        for m in s["masks"]:
            e = ck["masks"]["%d:%d" % (m, si)]
            bk = max(bk, e[0]); hits += e[1]; vh += e[2]
        dec = decode(bk)
        rec = {"shape": si, "ids": s["ids"], "chars": [glyphs_by_id[i]["ch"] for i in s["ids"]], "tier": min(glyphs_by_id[i]["tier"] for i in s["ids"]),
               "strokes": min(bin(m).count("1") if m != SPACEKEY else 0 for m in s["masks"]), "n_masks": len(s["masks"]),
               "canvas_hits": hits, "hits": vh, "score": None, "s1": None, "s0": None}
        if dec:
            rec.update(score=dec["score"], s1=dec["s1"], s0=pg.s1_to_s0(dec["s1"]))
        res.append(rec)
    done = ck["next_s1"] if ck.get("phase", 0) == 0 else __import__("planted_gpu").S1_LIMIT
    atomic_write(os.path.join(OUT, "glyph_best%s.json" % tag), json.dumps({
        "next_s1": done, "pos": ck["next_s1"], "phase": ck.get("phase", 0), "wrap_end": ck.get("wrap_end", 0), "trees_done": done, "hh_trees": ck["hh_trees"], "elapsed_s": ck["elapsed_s"],
        "updated": time.strftime("%Y-%m-%d %H:%M:%S"), "shapes": res}, indent=1))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt-seconds", type=float, default=30.0)
    ap.add_argument("--batch-log2", type=int, default=26)
    ap.add_argument("--sleep", type=float, default=0.0)
    ap.add_argument("--limit", type=int, default=0, help="testing: stop after this many trees (phase 0 only)")
    ap.add_argument("--ckpt", default="glyph_checkpoint.json")
    ap.add_argument("--test", action="store_true", help="test run: separate lock/best/log files, STOP file ignored")
    a = ap.parse_args()
    global LOGNAME
    tag = ".test" if a.test else ""
    LOGNAME = "glyph_progress%s.log" % tag
    os.makedirs(OUT, exist_ok=True)
    stop_path = os.path.join(OUT, "glyph_STOP")
    if os.path.exists(stop_path) and not a.test:
        print("out/glyph_STOP exists - delete it to run."); return 3
    fd = os.open(os.path.join(OUT, "glyph_sweep%s.lock" % tag), os.O_RDWR | os.O_CREAT)
    os.lseek(fd, 0, 0)
    try:
        msvcrt.locking(fd, msvcrt.LK_NBLCK, 1)
    except OSError:
        print("another glyph sweep holds out/glyph_sweep.lock"); return 4
    import cupy as cp
    import planted_gpu as pg
    glyphs = load_targets()
    gby = {g["id"]: g for g in glyphs}
    shapes, masks = build_shapes(glyphs)
    thash = hashlib.sha1(json.dumps(["score-v11", masks, [[sh["ids"], sh["masks"]] for sh in shapes]]).encode()).hexdigest()
    tab, slot_of = table_array(masks)
    M = 1 << TBITS
    # entries: one per (mask, shape) -- masks shared by several shapes get chained slots so every shape is scored with ITS OWN stemQuality
    ents = []          # (mask, shape index)
    for si, sh in enumerate(shapes):
        for m in sh["masks"]:
            ents.append((m, si))
    E = len(ents)
    slot_ent = {}; extra = 0
    alt = np.full(M + E, -1, dtype=np.int32)
    sq = np.zeros(M + E, dtype=np.uint32); adj = np.zeros(M + E, dtype=np.uint64)
    used = {}
    squal = [mask_quality([gby[i] for i in sh["ids"]]) for sh in shapes]
    for (m, si) in ents:
        if m not in used:
            sl = slot_of[m]; used[m] = sl
        else:
            sl = M + extra; extra += 1
            last = used[m]
            while alt[last] >= 0:
                last = alt[last]
            alt[last] = sl
        slot_ent[(m, si)] = sl
        sq[sl], adj[sl] = squal[si][m]
    log("targets: %d glyphs -> %d shapes, %d masks, %d (mask,shape) entries, table load %.3f (TBITS %d)" % (len(glyphs), len(shapes), len(masks), E, len(masks) / (1 << TBITS), TBITS))
    NS = M + E
    ek = lambda m, si: "%d:%d" % (m, si)

    mod = cp.RawModule(code=build_source(), options=("-std=c++14",), backend="nvrtc")
    k = mod.get_function("glyph")
    d_tab = cp.asarray(tab); d_sq = cp.asarray(sq); d_adj = cp.asarray(adj); d_alt = cp.asarray(alt)
    best = cp.zeros(NS, dtype=cp.uint64); cnt = cp.zeros(NS, dtype=cp.uint32); cntV = cp.zeros(NS, dtype=cp.uint32); hhc = cp.zeros(1, dtype=cp.uint64)

    ckp = os.path.join(OUT, a.ckpt)
    if os.path.exists(ckp):
        ck = json.load(open(ckp))
        if ck.get("ver", 1) < 5 or ck["thash"] != thash:
            # ---- migrate: keep progress, re-score each (mask, shape) best s1 with the new kernel (stemQuality / weighted backdrop)
            old = ck["masks"]
            log("MIGRATE v%d -> v5 (targets/score changed): re-scoring old bests, wrap_end=%d" % (ck.get("ver", 1), ck.get("wrap_end", ck["next_s1"])))
            newmasks = {}
            nlost = 0
            # shape indices shift when targets are added: match old entries by the shape's first glyph id when known
            oldsi = {sid: i for i, sid in enumerate(ck.get("shape_ids", []))}
            for (m, si) in ents:
                osi = oldsi.get(shapes[si]["ids"][0], si) if oldsi else si
                e = list(old.get(ek(m, osi)) or old.get(str(m)) or [0, 0, 0])
                if e[0]:
                    okm = (1 << ck.get("keybits", 48)) - 1      # entries written before the key format change used 48 bits
                    s1v = okm - (e[0] & okm)
                    best[...] = 0
                    k((1,), (1,), (np.uint64(s1v), np.uint64(1), d_tab, d_sq, d_adj, d_alt, best, cnt, cntV, hhc))
                    cp.cuda.Device().synchronize()
                    kk = int(best.get()[slot_ent[(m, si)]])
                    if not kk:
                        nlost += 1; log("  WARN old best s1=%d for mask %d no longer valid" % (s1v, m))
                    e[0] = kk
                newmasks[ek(m, si)] = e
            best[...] = 0; cnt[...] = 0; cntV[...] = 0; hhc[...] = 0
            ck["masks"] = newmasks; ck["thash"] = thash; ck["keybits"] = KEYBITS
            if ck.get("ver", 1) < 5:
                ck["wrap_end"] = ck["next_s1"]; ck["phase"] = 0
            elif ck.get("phase", 0) == 0:
                # new targets were only searched from next_s1 on: the wrap pass must re-cover everything before it
                ck["wrap_end"] = max(ck.get("wrap_end", 0), ck["next_s1"])
            ck["ver"] = 5
            log("migration done (%d invalid)" % nlost)
        elif ck["thash"] != thash:
            print("targets changed since checkpoint (mask set differs); move glyph_checkpoint.json away to restart"); return 5
    else:
        ck = {"ver": 5, "thash": thash, "next_s1": 0, "wrap_end": 0, "phase": 0, "elapsed_s": 0.0, "sessions": 0, "hh_trees": 0,
              "masks": {ek(m, si): [0, 0, 0] for (m, si) in ents}}
    ck["sessions"] += 1
    base = {e_: list(ck["masks"][ek(*e_)]) for e_ in ents}
    bh = np.zeros(NS, dtype=np.uint64)
    for e_ in ents:
        bh[slot_ent[e_]] = base[e_][0]
    best[...] = cp.asarray(bh)
    pos = ck["next_s1"]
    batch = 1 << a.batch_log2

    def cur_limit():
        if ck["phase"] == 0:
            return pg.S1_LIMIT if not a.limit else min(a.limit, pg.S1_LIMIT)
        if ck["phase"] == 1:
            return ck["wrap_end"]
        if ck["phase"] == 2:
            return pg.S1_LIMIT      # bonus: continue from the original wrap point (extra sureness)
        return pg.S1_LIMIT          # phase 3+: full extra passes until STOP
    log("START/RESUME phase %d next_s1=%d (%.4f%%) session %d" % (ck["phase"], pos, 100.0 * pos / max(cur_limit(), 1), ck["sessions"]))
    el_base = ck["elapsed_s"]; t_sess = time.time()
    t_ck = time.time(); pos_ck = pos
    stopped = False
    finished = False
    pending = []
    while True:
        limit = cur_limit()
        if pos >= limit:
            if ck["phase"] == 0 and not a.limit and ck["wrap_end"] > 0:
                log("main range done: WRAP to re-sweep [0, %d) with the new scoring" % ck["wrap_end"])
                ck["phase"] = 1; pos = 0; pos_ck = 0
                continue
            if ck["phase"] == 1 and not a.limit:
                log("wrap pass done: BONUS pass continues from wrap_end=%d to the end, then full passes until STOP" % ck["wrap_end"])
                ck["phase"] = 2; pos = ck["wrap_end"]; pos_ck = pos
                continue
            if ck["phase"] >= 2 and not a.limit:
                ck["phase"] = 3; pos = 0; pos_ck = 0
                log("bonus pass done: starting another full pass from 0 (until STOP)")
                continue
            finished = True
        if not finished:
            n = min(batch, limit - pos)
            k((1024,), (128,), (np.uint64(pos), np.uint64(n), d_tab, d_sq, d_adj, d_alt, best, cnt, cntV, hhc))
            # keep up to 2 batches queued so the GPU never idles between launches;
            # blocking-sync events let the CPU sleep instead of spin while it waits
            ev = cp.cuda.Event(block=True, disable_timing=True); ev.record()
            pending.append(ev)
            if len(pending) > 2:
                pending.pop(0).synchronize()
            pos += n
            if a.sleep:
                time.sleep(a.sleep)
        now = time.time()
        stop = os.path.exists(stop_path) and not a.test
        if finished or now - t_ck >= a.ckpt_seconds or pos >= limit or stop:
            cp.cuda.Device().synchronize(); pending.clear()
            c = cnt.get(); cv = cntV.get(); b = best.get()
            cnt[...] = 0; cntV[...] = 0
            for e_ in ents:
                e = base[e_]; sl = slot_ent[e_]
                e[0] = max(e[0], int(b[sl])); e[1] += int(c[sl]); e[2] += int(cv[sl])
                ck["masks"][ek(*e_)] = e
            ck["hh_trees"] += int(hhc.get()[0]); hhc[...] = 0
            ck["next_s1"] = pos
            ck["shape_ids"] = [sh["ids"][0] for sh in shapes]
            ck["elapsed_s"] = el_base + (now - t_sess)
            atomic_write(ckp, json.dumps(ck))
            write_best(ck, shapes, gby, tag)
            rate = (pos - pos_ck) / max(now - t_ck, 1e-9)
            nfound = sum(1 for s in json.load(open(os.path.join(OUT, "glyph_best%s.json" % tag)))["shapes"] if s["s1"] is not None)
            log("progress phase %d %.5f%%  next_s1=%d  rate %.3e trees/s  ETA %.1f h  shapes with a hit %d/%d" % (
                ck["phase"], 100.0 * pos / max(limit, 1), pos, rate, (limit - pos) / max(rate, 1) / 3600, nfound, len(shapes)))
            t_ck = time.time(); pos_ck = pos
            if stop:
                stopped = True
                log("STOP file seen: checkpoint written, exiting cleanly")
                break
            if finished:
                break
    if not stopped:
        log("ALL DONE (main range + wrap)" if finished and not a.limit else "limit reached (test run)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
