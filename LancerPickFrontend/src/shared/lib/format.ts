const won = new Intl.NumberFormat('ko-KR')
export function formatWon(amount: number) { return `${won.format(amount)}원` }

/** API의 시간대 포함 ISO 시각을 화면에서 한국 시간으로 표시한다. */
export function formatDateTime(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul', dateStyle: 'medium', timeStyle: 'short',
  }).format(new Date(value))
}
