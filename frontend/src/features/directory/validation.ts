function hasControl(value: string, allowMemoWhitespace = false): boolean {
  return [...value].some((character) => {
    const code = character.codePointAt(0)!;
    if (allowMemoWhitespace && (code === 9 || code === 10 || code === 13)) return false;
    return code <= 31 || (code >= 127 && code <= 159);
  });
}

export function nameError(value: string, max: number, label: string): string | null {
  const trimmed = value.trim();
  if (!trimmed) return `${label}을 입력해 주세요.`;
  if ([...trimmed].length > max) return `${label}은 ${max}자 이내로 입력해 주세요.`;
  if (hasControl(trimmed)) return `${label}에 제어 문자는 사용할 수 없어요.`;
  return null;
}

export function songError(title: string, artist: string, memo: string, sortOrder: string): string | null {
  const titleError = nameError(title, 120, "곡 제목");
  if (titleError) return titleError;
  if ([...artist.trim()].length > 120) return "아티스트는 120자 이내로 입력해 주세요.";
  if (hasControl(artist.trim())) return "아티스트에 제어 문자는 사용할 수 없어요.";
  if ([...memo.trim()].length > 1000) return "메모는 1,000자 이내로 입력해 주세요.";
  if (hasControl(memo.trim(), true)) return "메모에 사용할 수 없는 제어 문자가 있어요.";
  if (sortOrder !== "" && (!/^\d+$/.test(sortOrder) || !Number.isSafeInteger(Number(sortOrder)) || Number(sortOrder) > 2147483647)) return "순서는 0 이상 2,147,483,647 이하의 정수로 입력해 주세요.";
  return null;
}
