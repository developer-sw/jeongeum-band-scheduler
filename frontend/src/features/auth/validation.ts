export function normalizeLoginId(value: string) { return value.trim().toLowerCase(); }

export function loginIdError(value: string): string | undefined {
  return /^[a-zA-Z0-9][a-zA-Z0-9._-]{2,31}$/.test(value.trim()) ? undefined : "아이디는 영문·숫자로 시작하고 영문·숫자·점·밑줄·하이픈 3~32자로 입력해 주세요.";
}

export function passwordError(value: string): string | undefined {
  if (value.includes("\0")) return "비밀번호에 사용할 수 없는 문자가 있어요.";
  if (Array.from(value).length < 12) return "비밀번호는 12자 이상으로 입력해 주세요.";
  if (new TextEncoder().encode(value).length > 72) return "비밀번호는 UTF-8 기준 72바이트 이내로 입력해 주세요. 한글은 더 많은 바이트를 사용해요.";
}

export function displayNameError(value: string): string | undefined {
  const length = Array.from(value.trim()).length;
  const hasControl = Array.from(value).some((character) => {
    const code = character.codePointAt(0)!;
    return code <= 31 || (code >= 127 && code <= 159);
  });
  return length >= 1 && length <= 40 && !hasControl ? undefined : "이름은 앞뒤 공백을 제외하고 1~40자로 입력해 주세요. 제어 문자는 사용할 수 없어요.";
}
