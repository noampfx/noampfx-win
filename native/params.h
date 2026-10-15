#pragma once
// Urutan ID harus sama dengan Params.kt
enum ParamId {
    GATE_ON=0, GATE_THR, GATE_ATK, GATE_REL, GATE_RANGE,
    COMP_ON, COMP_THR, COMP_RATIO, COMP_ATK, COMP_REL, COMP_MAKEUP, COMP_KNEE,
    NAM_ON, NAM_IN, NAM_OUT, NAM_RES,
    CAB_ON, CAB_LEVEL, CAB_MIX,
    EQ_ON, EQ_B0 /*20..34: freq,gain,q x5*/ = 20, EQ_OUT = 35,
    MOD_ON, MOD_TYPE, MOD_RATE, MOD_DEPTH, MOD_MIX, MOD_FB,
    DLY_ON, DLY_TIME, DLY_FB, DLY_MIX, DLY_TONE,
    REV_ON, REV_SIZE, REV_DAMP, REV_MIX,
    LIM_ON = 52, LIM_THR, LIM_CEIL, LIM_REL,
    MASTER_IN = 57, MASTER_OUT = 58,
    DLY_MODE = 59 /*0 ms,1 straight,2 triplet,3 dotted*/, DLY_DIV = 60 /*0..5: 1/1..1/32*/, DLY_BPM = 61,
    PITCH_ON = 64, PITCH_ST, PITCH_FINE, PITCH_MIX,
    DRV_ON = 70 /*68-69 reserved*/, DRV_TYPE /*0 Screamer,1 Precision,2 Centaur,3 Overdrive,4 Fuzz*/, DRV_GAIN, DRV_TONE, DRV_LEVEL,
    DLY_TYPE = 75 /*0 digital,1 tape*/, DLY_DUCK, REV_TYPE /*0 room/hall,1 shimmer*/, REV_SHIM, GATE_POS /*0 input,1 before drive*/,
    WAH_ON = 80, WAH_MODE /*0 pedal,1 auto*/, WAH_POS, WAH_Q, WAH_SENS,
    OCT_ON = 85, OCT_DOWN, OCT_UP, OCT_DRY,
    HARM_ON = 89, HARM_KEY, HARM_SCALE, HARM_INT, HARM_MIX,
    CAB2_MIX = 94 /*blend IR slot A -> B*/,
    CHAIN0 = 96 /*96..111: urutan blok (entri = bank*16 + kode modul 1..13, 0 = kosong)*/, CHAIN_VER = 112,
    PARAM_COUNT = 128
};
/** Blok kembar: 2 bank tambahan salinan penuh parameter (id + bank * PARAM_BANK). Entri chain = bank * 16 + kode modul. */
constexpr int PARAM_BANK = 128, PARAM_BANKS = 3, PARAM_TOTAL = PARAM_BANK * PARAM_BANKS;
constexpr int CHAIN_SLOTS = 8;   // 8 slot blok bebas (entri 0 = kosong); gate tetap di awal (input) dan limiter di akhir (output)
