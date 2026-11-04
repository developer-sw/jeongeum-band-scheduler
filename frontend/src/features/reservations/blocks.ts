import type { BlockCode } from "./types";

// Codes are stable reservation references; display order follows the clock.
export const blockTimes = {
  B11: "00:00~01:00", B12: "01:00~02:00", B13: "02:00~03:00", B14: "03:00~04:00",
  B15: "04:00~05:00", B16: "05:00~06:00", B17: "06:00~07:00", B18: "07:00~08:00",
  B19: "08:00~09:00", B20: "09:00~10:00", B21: "10:00~11:00", B22: "11:00~12:00",
  B01: "12:00~13:00", B02: "13:00~14:00", B03: "14:00~15:00", B04: "15:00~16:00",
  B05: "16:00~17:00", B06: "17:00~18:00", B07: "18:00~19:30", B08: "19:30~21:00",
  B09: "21:00~22:30", B10: "22:30~24:00",
} satisfies Record<BlockCode, string>;

export const blockNames = Object.fromEntries(Object.entries(blockTimes).map(([code, time]) => [
  code, `${time} · ${["B07", "B08", "B09", "B10"].includes(code) ? 90 : 60}분`,
])) as Record<BlockCode, string>;
