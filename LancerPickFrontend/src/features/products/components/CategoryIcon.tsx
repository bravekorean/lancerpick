import { Icon } from '../../../shared/components/Icon'
import type { IconName } from '../../../shared/components/Icon'

const icons: Record<string, IconName> = { 디자인: 'design', 개발: 'code', 영상: 'video', 마케팅: 'marketing', 번역: 'translate', 문서: 'document' }
export function CategoryIcon({ name, size = 24 }: { name: string; size?: number }) {
  return <Icon name={icons[name] ?? 'document'} size={size} />
}
