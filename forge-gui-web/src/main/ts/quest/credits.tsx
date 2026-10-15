// Quest's currency: its skin icon, and a price in it

import { skinIconUrl } from '../images';
import { formatNumber, t } from '../text';

/** The skin icon Quest's credits are drawn with. */
export const CREDITS = 'ICO_QUEST_COINSTACK';

/** A price in credits, as the shop, the challenges and the tournaments show it. */
export function Price({ n }: { n: number }) {
  return <span class="qu-price"><img alt={t('lblCredits')} src={skinIconUrl(CREDITS)} />{formatNumber(n)}</span>;
}
