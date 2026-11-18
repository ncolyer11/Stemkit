"""GPU kernels for the exhaustive planted (bonemeal) huge-fungus search.

Imported by glyph_sweep.py and sibling_gpu.py (needs CuPy and an NVIDIA GPU).

Key idea: s0 -> s1 = LCG(s0) is a bijection on 2^48.  The 0.4 success roll is
(s1 >> 24) < 6710887, i.e. a CONTIGUOUS range of s1 = [0, 6710887 * 2^24).  So the sweep enumerates s1 directly
(every thread does useful work, no divergence from the 60% failures) and s0 is recovered on the host via the
modular inverse of the multiplier.  The tree is simulated from s1.
"""
import math
from fractions import Fraction
import numpy as np
import cupy as cp

MASK48 = (1 << 48) - 1
MUL = 25214903917
INC = 11
MUL_INV = pow(MUL, -1, 1 << 48)
S1_LIMIT = 6710887 << 24          # number of s1 values that pass the 0.4 roll (= trees per variant)
NSHBINS = 256
NWBINS = 512


def s0_to_s1(s0):
    return (s0 * MUL + INC) & MASK48


def s1_to_s0(s1):
    return ((s1 - INC) * MUL_INV) & MASK48


def thr(f):
    """n (24-bit int) satisfies  float(n * 2^-24) < f   <=>  n < ceil(f * 2^24)   (f given exactly)"""
    x = Fraction(f) * (1 << 24)
    return math.ceil(x)


def f32(x):
    return float(np.float32(x))


KERNEL = r"""
typedef unsigned long long u64;
typedef unsigned int u32;
#define MASK 0xFFFFFFFFFFFFULL

__device__ __forceinline__ u32 nxt(u64 &s, int bits) {
    s = (s * 0x5DEECE66DULL + 11ULL) & MASK;
    return (u32)(s >> (48 - bits));
}
__device__ __forceinline__ int nextInt(u64 &s, int bound) {
    if ((bound & (bound - 1)) == 0) return (int)(((u64)bound * (u64)nxt(s, 31)) >> 31);
    int sample, modulo;
    do {
        sample = (int)nxt(s, 31);
        modulo = sample % bound;
    } while ((int)((u32)(sample - modulo) + (u32)(bound - 1)) < 0);
    return modulo;
}

struct Res { int shroom, wart, vines, H; u64 fin; };

// Vine events (crimson).  A vine hangs below a hat block; every later vine start sees only hat/shroomlight/stem/ground
// (never another vine: a vine from the hat at layer a occupies y<=a-1 below that hat, and later hats are above it).
// Hence for shroom/wart/final-RNG-state purposes a vine event is exactly 3 draws (nextInt(5), nextInt(7), nextInt(3) age)
// iff the cell directly below the hat block is empty (layer DY-1 has no block in that column, not the stem column, DY>=1),
// else 0 draws.  Rejection-loop draws (sample >= floor(2^31/b)*b, prob ~1e-9) are handled by a slow exact path.
#define TRYVINES(IDX, DY) \
    { \
        if ((IDX) != 24 && (DY) >= 1 && !((prevMask >> (IDX)) & 1ULL)) { \
            u64 sa = (s * 0x5DEECE66DULL + 11ULL) & MASK; \
            u64 sb = (sa * 0x5DEECE66DULL + 11ULL) & MASK; \
            u64 sc = (sb * 0x5DEECE66DULL + 11ULL) & MASK; \
            if (((u32)(sa >> 17) >= 2147483645u) | ((u32)(sb >> 17) >= 2147483646u) | ((u32)(sc >> 17) >= 2147483646u)) { \
                nextInt(s, 5); nextInt(s, 7); nextInt(s, 3); \
            } else s = sc; \
        } \
    }

__device__ __forceinline__ void sim(u64 s, Res &r) {
    int shroom = 0, wart = 0;
    int H = nextInt(s, 10) + 4;
    if (nextInt(s, 12) == 0) H *= 2;
    int hh = nextInt(s, 1 + H / 3) + 5;
    if (hh > H) hh = H;
    int start = H - hh;
    u64 prevMask = 0;      // blocks (wart/shroomlight) of layer dy-1, bit (dx+3)*7+dz+3
    for (int dy = start; dy <= H; dy++) {
        int rr = nextInt(s, 3);
        int radius = dy < H - rr ? 2 : 1;
        if (hh > 8 && dy < start + 4) radius = 3;
        bool bottom = dy < start + 3;
        u64 curMask = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            bool ex = (dx == -radius) || (dx == radius);
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0 && dy < H) continue;        // stem cell: not replaceable
                bool ez = (dz == -radius) || (dz == radius);
                bool inside = !ex && !ez && dy != H;
                bool corner = ex && ez;
                int idx = (dx + 3) * 7 + (dz + 3);
                if (bottom) {
                    if (inside) continue;
                    // in the three drop layers the layer below only ever holds wart, so prevMask == "below is hat_state"
                    if ((prevMask >> idx) & 1ULL) {
                        wart++; curMask |= 1ULL << idx;
                    } else if (nxt(s, 24) < THR_DROP) {
                        wart++; curMask |= 1ULL << idx;
#if VINES
                        if (nextInt(s, 11) == 0) TRYVINES(idx, dy)
#endif
                    }
                } else {
                    u32 tD, tH;
#if VINES
                    u32 tV;
                    if (inside) { tD = THR_D_IN; tH = THR_H_IN; tV = THR_V_IN; }
                    else if (corner) { tD = THR_D_CO; tH = THR_H_CO; tV = THR_V_CO; }
                    else { tD = THR_D_ED; tH = THR_H_ED; tV = THR_V_ED; }
#else
                    if (inside) { tD = THR_D_IN; tH = THR_H_IN; }
                    else if (corner) { tD = THR_D_CO; tH = THR_H_CO; }
                    else { tD = THR_D_ED; tH = THR_H_ED; }
#endif
                    if (nxt(s, 24) < tD) {
                        shroom++; curMask |= 1ULL << idx;
                    } else if (nxt(s, 24) < tH) {
                        wart++; curMask |= 1ULL << idx;
#if VINES
                        if (nxt(s, 24) < tV) TRYVINES(idx, dy)
#else
                        nxt(s, 24);   // warped: vinesProbability 0.0F, nextFloat still consumed
#endif
                    }
                }
            }
        }
        prevMask = curMask;
    }
    r.shroom = shroom; r.wart = wart; r.vines = 0; r.H = H; r.fin = s;
}

// validation: in[i] = s0 (mode 0, includes roll) or s1 (mode 1)
extern "C" __global__ void validate(const u64 *in, int n, int mode, int *ok, int *shroom, int *wart, int *stem, int *vines, u64 *fin) {
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i >= n) return;
    u64 s = in[i];
    if (mode == 0) {
        s = (s * 0x5DEECE66DULL + 11ULL) & MASK;
        if (!((s >> 24) < THR_SUCCESS)) { ok[i] = 0; shroom[i] = wart[i] = stem[i] = vines[i] = 0; fin[i] = 0; return; }
    }
    Res r; sim(s, r);
    ok[i] = 1; shroom[i] = r.shroom; wart[i] = r.wart; stem[i] = r.H; vines[i] = r.vines; fin[i] = r.fin;
}

// sweep over s1 in [start, start+count)
extern "C" __global__ void sweep(u64 start, u64 count, int T, u64 *hs, u64 *hw, u64 *maxw,
                                  u64 *logS1, u32 *logMeta, u32 *logCount, u32 logCap) {
    __shared__ u32 sh_s[NSHB];
    __shared__ u32 sh_w[NWB];
    for (int i = threadIdx.x; i < NSHB; i += blockDim.x) sh_s[i] = 0;
    for (int i = threadIdx.x; i < NWB; i += blockDim.x) sh_w[i] = 0;
    __syncthreads();
    u64 nthreads = (u64)gridDim.x * blockDim.x;
    u64 tid = (u64)blockIdx.x * blockDim.x + threadIdx.x;
    u64 iters = (count + nthreads - 1) / nthreads;
    u64 best = 0;
    int lane = threadIdx.x & 31;
    for (u64 it = 0; it < iters; it++) {
        u64 idx = tid + it * nthreads;
        bool valid = idx < count;
        int vs = -1, vw = -1;
        if (valid) {
            u64 s1 = start + idx;
            Res r; sim(s1, r);
            vs = min(r.shroom, NSHB - 1);
            vw = min(r.wart, NWB - 1);
            u64 key = ((u64)r.wart << 48) | s1;
            if (key > best) best = key;
            if (r.shroom >= T) {
                u32 p = atomicAdd(logCount, 1u);
                if (p < logCap) { logS1[p] = s1; logMeta[p] = (u32)r.shroom | ((u32)r.wart << 8) | ((u32)r.vines << 20); }
            }
        }
        // warp-aggregated shared histogram updates
        u32 m = __match_any_sync(0xffffffffu, vs);
        if (vs >= 0 && lane == __ffs(m) - 1) atomicAdd(&sh_s[vs], (u32)__popc(m));
        m = __match_any_sync(0xffffffffu, vw);
        if (vw >= 0 && lane == __ffs(m) - 1) atomicAdd(&sh_w[vw], (u32)__popc(m));
    }
    if (best) atomicMax(maxw, best);
    __syncthreads();
    for (int i = threadIdx.x; i < NSHB; i += blockDim.x) if (sh_s[i]) atomicAdd(&hs[i], (u64)sh_s[i]);
    for (int i = threadIdx.x; i < NWB; i += blockDim.x) if (sh_w[i]) atomicAdd(&hw[i], (u64)sh_w[i]);
}
"""


def defines(crimson):
    d = {
        "VINES": 1 if crimson else 0,
        "NSHB": NSHBINS, "NWB": NWBINS,
        "THR_SUCCESS": thr(Fraction(0.4)) ,
        "THR_DROP": thr(Fraction(0.15)),
        "THR_D_IN": thr(f32(0.1)), "THR_H_IN": thr(f32(0.2)), "THR_V_IN": thr(f32(0.1)) if crimson else 0,
        "THR_D_CO": thr(f32(0.01)), "THR_H_CO": thr(f32(0.7)), "THR_V_CO": thr(f32(0.083)) if crimson else 0,
        "THR_D_ED": thr(f32(5.0e-4)), "THR_H_ED": thr(f32(0.98)), "THR_V_ED": thr(f32(0.07)) if crimson else 0,
    }
    return d


_mods = {}


def get_module(crimson):
    if crimson not in _mods:
        d = defines(crimson)
        src = "".join("#define %s %du\n" % (k, v) if k.startswith("THR") else "#define %s %d\n" % (k, v) for k, v in d.items()) + KERNEL
        _mods[crimson] = cp.RawModule(code=src, options=("-std=c++14",), backend="nvrtc")
    return _mods[crimson]


def validate_batch(crimson, states, mode):
    """states: np.uint64 array. returns dict of numpy arrays."""
    mod = get_module(crimson)
    k = mod.get_function("validate")
    n = len(states)
    d_in = cp.asarray(states.astype(np.uint64))
    outs = {name: cp.zeros(n, dtype=dt) for name, dt in
            [("ok", cp.int32), ("shroom", cp.int32), ("wart", cp.int32), ("stem", cp.int32), ("vines", cp.int32), ("fin", cp.uint64)]}
    k(((n + 127) // 128,), (128,), (d_in, np.int32(n), np.int32(mode), outs["ok"], outs["shroom"], outs["wart"], outs["stem"], outs["vines"], outs["fin"]))
    cp.cuda.Device().synchronize()
    return {k_: v.get() for k_, v in outs.items()}


class Sweeper:
    def __init__(self, crimson, T, grid=2048, block=128, log_cap=1 << 20):
        self.crimson = crimson
        self.T = T
        self.grid, self.block = grid, block
        self.mod = get_module(crimson)
        self.k = self.mod.get_function("sweep")
        self.log_cap = log_cap
        self.hs = cp.zeros(NSHBINS, dtype=cp.uint64)
        self.hw = cp.zeros(NWBINS, dtype=cp.uint64)
        self.maxw = cp.zeros(1, dtype=cp.uint64)
        self.logS1 = cp.zeros(log_cap, dtype=cp.uint64)
        self.logMeta = cp.zeros(log_cap, dtype=cp.uint32)
        self.logCount = cp.zeros(1, dtype=cp.uint32)

    def reset(self):
        for a in (self.hs, self.hw, self.maxw, self.logCount):
            a[...] = 0

    def run(self, start, count):
        """Process s1 in [start, start+count).  Accumulates into device histograms; returns nothing."""
        self.k((self.grid,), (self.block,), (np.uint64(start), np.uint64(count), np.int32(self.T), self.hs, self.hw, self.maxw,
                                             self.logS1, self.logMeta, self.logCount, np.uint32(self.log_cap)))

    def sync(self):
        cp.cuda.Device().synchronize()

    def fetch_and_clear_log(self):
        n = int(self.logCount.get()[0])
        if n > self.log_cap:
            raise RuntimeError("log overflow %d > %d" % (n, self.log_cap))
        s1 = self.logS1[:n].get().copy()
        meta = self.logMeta[:n].get().copy()
        self.logCount[...] = 0
        return s1, meta
