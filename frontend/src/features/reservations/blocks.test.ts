import { describe, expect, it } from "vitest";
import { blockNames, blockTimes } from "./blocks";

const minutes = (value: string) => Number(value.slice(0, 2)) * 60 + Number(value.slice(3));

describe("full-day canonical block labels", () => {
  it("preserves existing reservation codes and covers midnight through 24:00 without gaps", () => {
    expect(blockTimes.B01).toBe("12:00~13:00");
    expect(blockTimes.B07).toBe("18:00~19:30");
    expect(blockTimes.B10).toBe("22:30~24:00");
    const ranges = Object.values(blockTimes).map((label) => label.split("~").map(minutes));
    expect(ranges).toHaveLength(22);
    expect(ranges[0][0]).toBe(0);
    expect(ranges.at(-1)?.[1]).toBe(1440);
    ranges.forEach(([start, end], index) => {
      if (index) expect(start).toBe(ranges[index - 1][1]);
      expect(end - start).toBe(start < 1080 ? 60 : 90);
    });
  });

  it("offers recurring slots in clock order and labels the new early exchange destinations", () => {
    expect(Object.keys(blockNames)).toEqual(Object.keys(blockTimes));
    expect(Object.values(blockNames)[0]).toBe("00:00~01:00 · 60분");
    expect(blockNames.B22).toBe("11:00~12:00 · 60분");
    expect(blockNames.B07).toBe("18:00~19:30 · 90분");
  });
});
