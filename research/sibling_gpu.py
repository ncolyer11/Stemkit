"""GPU sibling search: grows every s1 as warped AND crimson and logs the pairs whose blocks match (weeping vines ignored).

  python.exe -I sibling_gpu.py --minutes 30                 # resumable contiguous sweep over s1 (checkpoint out/sibling_gpu_ckpt.json)
  python.exe -I sibling_gpu.py --validate                   # compare with the Java reference (PlantedRef siblings) first
  python.exe -I sibling_gpu.py --bench 20                   # 20 s throughput test, no results written

Same tree code as planted_gpu.py's kernel (validated against PlantedRef on the exhaustive sweep): per hat layer a 49-bit
occupancy mask and a shroomlight mask. Warped is simulated first and its masks are kept; crimson is then simulated and
compared layer by layer, stopping at the first layer whose occupancy differs (exact: a pair that differs anywhere is not a
sibling). SHAPE = same occupancy in every layer; IDENTICAL = also the same shroomlight cells. Stems always match (same H).
s1 enumerates [0, S1_LIMIT): exactly the trees that pass the 0.4 bone-meal roll; s0 = s1_to_s0(s1).

The GPU is shared with glyph_sweep.py if it runs: both slow down while this runs.
"""
import os, sys, json, time, argparse, subprocess
import numpy as np
import cupy as cp

HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
OUT = os.path.join(HERE, "out")
import planted_gpu as pg

KERNEL = pg.KERNEL.split("__device__ __forceinline__ void sim(")[0] + r"""
#define MAXL 28

// one hat, layer by layer. CR=false: record masks; CR=true: compare against them, return false at the first shape mismatch.
template <bool CR>
__device__ __forceinline__ bool simSib(u64 s, u64 *occ, u64 *shr, int &shroom, int &wart, int &H, int &typeDiff) {
    shroom = 0; wart = 0; typeDiff = 0;
    H = nextInt(s, 10) + 4;
    if (nextInt(s, 12) == 0) H *= 2;
    int hh = nextInt(s, 1 + H / 3) + 5;
    if (hh > H) hh = H;
    int start = H - hh;
    u64 prevMask = 0;
    for (int dy = start; dy <= H; dy++) {
        int rr = nextInt(s, 3);
        int radius = dy < H - rr ? 2 : 1;
        if (hh > 8 && dy < start + 4) radius = 3;
        bool bottom = dy < start + 3;
        u64 curMask = 0, curShr = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            bool ex = (dx == -radius) || (dx == radius);
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0 && dy < H) continue;
                bool ez = (dz == -radius) || (dz == radius);
                bool inside = !ex && !ez && dy != H;
                bool corner = ex && ez;
                int idx = (dx + 3) * 7 + (dz + 3);
                if (bottom) {
                    if (inside) continue;
                    if ((prevMask >> idx) & 1ULL) {
                        wart++; curMask |= 1ULL << idx;
                    } else if (nxt(s, 24) < THR_DROP) {
                        wart++; curMask |= 1ULL << idx;
                        if (CR) { if (nextInt(s, 11) == 0) TRYVINES(idx, dy) }
                    }
                } else {
                    u32 tD, tH, tV;
                    if (inside) { tD = THR_D_IN; tH = THR_H_IN; tV = THR_V_IN; }
                    else if (corner) { tD = THR_D_CO; tH = THR_H_CO; tV = THR_V_CO; }
                    else { tD = THR_D_ED; tH = THR_H_ED; tV = THR_V_ED; }
                    if (nxt(s, 24) < tD) {
                        shroom++; curMask |= 1ULL << idx; curShr |= 1ULL << idx;
                    } else if (nxt(s, 24) < tH) {
                        wart++; curMask |= 1ULL << idx;
                        if (CR) { if (nxt(s, 24) < tV) TRYVINES(idx, dy) }
                        else nxt(s, 24);          // warped: vine float still drawn, chance 0
                    }
                }
            }
        }
        int l = dy - start;
        if (CR) {
            if (curMask != occ[l]) return false;
            typeDiff += __popcll(curShr ^ shr[l]);
        } else { occ[l] = curMask; shr[l] = curShr; }
        prevMask = curMask;
    }
    return true;
}

// meta: identical<<31 | H<<24 | wart<<8 | shroom   (shroom = warped count; crimson differs only for SHAPE pairs)
__device__ __forceinline__ void one(u64 s1, u64 *logS1, u32 *logMeta, u32 *logCount, u32 logCap) {
    u64 occ[MAXL], shr[MAXL];
    int sw, ww, Hw, td, sc, wc, Hc;
    simSib<false>(s1, occ, shr, sw, ww, Hw, td);
    if (simSib<true>(s1, occ, shr, sc, wc, Hc, td)) {
        u32 p = atomicAdd(logCount, 1u);
        if (p < logCap) { logS1[p] = s1; logMeta[p] = ((td == 0) ? 0x80000000u : 0u) | ((u32)Hw << 24) | ((u32)ww << 8) | (u32)sw; }
    }
}

extern "C" __global__ void sib(u64 start, u64 count, u64 *logS1, u32 *logMeta, u32 *logCount, u32 logCap) {
    u64 nthreads = (u64)gridDim.x * blockDim.x;
    for (u64 i = (u64)blockIdx.x * blockDim.x + threadIdx.x; i < count; i += nthreads) one(start + i, logS1, logMeta, logCount, logCap);
}

extern "C" __global__ void sibList(const u64 *s1s, int n, u64 *logS1, u32 *logMeta, u32 *logCount, u32 logCap) {
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i < n) one(s1s[i], logS1, logMeta, logCount, logCap);
}
"""


def module():
    d = pg.defines(True)       # crimson thresholds (the warped pass ignores the vine ones)
    src = "".join("#define %s %d\n" % (k, v) for k, v in d.items()) + KERNEL
    return cp.RawModule(code=src, options=("-std=c++14",), backend="nvrtc")


class Runner:
    def __init__(self, cap=1 << 22, grid=4096, block=128):
        self.mod = module(); self.k = self.mod.get_function("sib"); self.kl = self.mod.get_function("sibList")
        self.cap, self.grid, self.block = cap, grid, block
        self.logS1 = cp.zeros(cap, dtype=cp.uint64); self.logMeta = cp.zeros(cap, dtype=cp.uint32)
        self.cnt = cp.zeros(1, dtype=cp.uint32)

    def run(self, start, count):
        self.k((self.grid,), (self.block,), (np.uint64(start), np.uint64(count), self.logS1, self.logMeta, self.cnt, np.uint32(self.cap)))

    def fetch(self):
        n = int(self.cnt.get()[0])
        if n > self.cap:
            raise RuntimeError("log overflow: %d hits > cap %d (use smaller chunks)" % (n, self.cap))
        s1 = self.logS1[:n].get(); meta = self.logMeta[:n].get()
        self.cnt[...] = 0
        return s1, meta


def decode(s1, meta):
    out = []
    for a, m in zip(s1.tolist(), meta.tolist()):
        out.append({"s0": pg.s1_to_s0(a), "s1": a, "kind": "IDENTICAL" if m >> 31 else "SHAPE", "height": (m >> 24) & 127,
                    "wart": (m >> 8) & 0xFFFF, "shroom": m & 0xFF})
    return out


def validate(n=3_000_000):
    """GPU hits on s1 in [0, n) must equal the Java reference hits exactly (kind, s1, warped shroom/wart/H)."""
    r = Runner()
    r.run(0, n); cp.cuda.Device().synchronize()
    g = {h["s1"]: h for h in decode(*r.fetch())}
    res = subprocess.run(["java", "-cp", os.path.join(HERE, "bin"), "PlantedRef", "siblings", "-", "0", str(n), "5"],
                         capture_output=True, text=True, check=True).stdout.split("\n")
    j = {}
    for line in res:
        p = line.split()
        if p and p[0] == "SIB":
            j[int(p[2])] = {"kind": "IDENTICAL" if p[1] == "1" else "SHAPE", "shroom": int(p[3]), "wart": int(p[5]), "height": int(p[6])}
    bad = [s for s in set(g) | set(j) if s not in g or s not in j or any(g[s][k] != j[s][k] for k in ("kind", "shroom", "wart", "height"))]
    print("validate s1 [0,%d): GPU %d hits, Java %d hits, mismatches %d" % (n, len(g), len(j), len(bad)))
    for s in bad[:10]:
        print("  ", s, g.get(s), j.get(s))
    return not bad


CATS = {   # category -> (sort key on numpy columns, filter); best first
    "most_shroom": lambda sh, wa, H: (sh * 1000 + wa + H, np.ones_like(sh, bool)),
    "glow_ratio": lambda sh, wa, H: (sh / np.maximum(wa, 1), sh >= 4),
    "biggest": lambda sh, wa, H: (sh + wa + H, np.ones_like(sh, bool)),
    "tallest": lambda sh, wa, H: (H * 10000 + sh * 100 + wa, np.ones_like(sh, bool)),
}
TOPK = 25


def merge_top(ck, s1, meta):
    """keep the TOPK identical siblings per category (numpy, so millions of hits per chunk are fine)"""
    ident = (meta >> 31) == 1
    ck["identical"] += int(ident.sum()); ck["shape_only"] += int((~ident).sum())
    s1, meta = s1[ident], meta[ident]
    sh = (meta & 0xFF).astype(np.int64); wa = ((meta >> 8) & 0xFFFF).astype(np.int64); H = ((meta >> 24) & 127).astype(np.int64)
    for cat, f in CATS.items():
        old = ck["top"].get(cat, [])
        key, ok = f(sh, wa, H)
        idx = np.nonzero(ok)[0]
        idx = idx[np.argsort(-key[idx], kind="stable")[:TOPK]]
        new = [{"s0": pg.s1_to_s0(int(s1[i])), "s1": int(s1[i]), "shroom": int(sh[i]), "wart": int(wa[i]), "height": int(H[i])} for i in idx]
        allc = {e["s1"]: e for e in old + new}.values()
        o_sh = np.array([e["shroom"] for e in allc]); o_wa = np.array([e["wart"] for e in allc]); o_H = np.array([e["height"] for e in allc])
        allc = list(allc)
        if not allc:
            ck["top"][cat] = []; continue
        k2, ok2 = f(o_sh, o_wa, o_H)
        order = [i for i in np.argsort(-k2, kind="stable") if ok2[i]][:TOPK]
        ck["top"][cat] = [allc[i] for i in order]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--minutes", type=float, default=30)
    ap.add_argument("--chunk-log2", type=int, default=31)
    ap.add_argument("--validate", action="store_true")
    ap.add_argument("--bench", type=float, default=0)
    ap.add_argument("--ckpt", default="sibling_gpu_ckpt.json")
    a = ap.parse_args()
    if a.validate:
        sys.exit(0 if validate() else 1)
    r = Runner()
    chunk = 1 << a.chunk_log2
    if a.bench:
        r.run(0, 1 << 24); cp.cuda.Device().synchronize(); r.fetch()      # warm-up / compile
        t0 = time.time(); pos = 1 << 24
        while time.time() - t0 < a.bench:
            r.run(pos, chunk); cp.cuda.Device().synchronize(); r.fetch(); pos += chunk
        dt = time.time() - t0
        print("bench: %.3g trees/s (%d trees in %.1f s)" % ((pos - (1 << 24)) / dt, pos - (1 << 24), dt))
        return
    ckp = os.path.join(OUT, a.ckpt)
    ck = json.load(open(ckp)) if os.path.exists(ckp) else {"next_s1": 0, "trees": 0, "identical": 0, "shape_only": 0, "top": {}}
    t0 = time.time(); pos = ck["next_s1"]; done0 = ck["trees"]
    while time.time() - t0 < 60 * a.minutes and pos < pg.S1_LIMIT:
        n = min(chunk, pg.S1_LIMIT - pos)
        r.run(pos, n); cp.cuda.Device().synchronize()
        merge_top(ck, *r.fetch()); pos += n; ck["trees"] += n; ck["next_s1"] = pos
        tmp = ckp + ".tmp"; json.dump(ck, open(tmp, "w"), indent=1); os.replace(tmp, ckp)
        print("%6.0fs  s1 %.4f%%  trees %.3g  identical %d  shape-only %d  %.3g trees/s" % (
            time.time() - t0, 100.0 * pos / pg.S1_LIMIT, ck["trees"], ck["identical"], ck["shape_only"],
            (ck["trees"] - done0) / max(1e-9, time.time() - t0)), flush=True)
    for cat in CATS:
        print(cat, [(e["s0"], e["shroom"], e["wart"], e["height"]) for e in ck["top"].get(cat, [])[:3]])
    print("checkpoint: out/%s (next_s1 %d)" % (a.ckpt, pos))


if __name__ == "__main__":
    main()
