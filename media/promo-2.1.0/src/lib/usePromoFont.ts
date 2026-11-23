import {useEffect, useState} from 'react';
import {continueRender, delayRender, staticFile} from 'remotion';

/**
 * Load the bundled Noto Sans SC before the first frame is captured. Remotion
 * otherwise screenshots before the CJK font settles, which shifts every line.
 */
export const usePromoFont = () => {
  const [handle] = useState(() => delayRender('Loading PromoSC'));
  useEffect(() => {
    let cancelled = false;
    const face = new FontFace(
      'PromoSC',
      `url(${staticFile('fonts/NotoSansSC.ttf')}) format('truetype')`,
      {weight: '100 900', style: 'normal'},
    );
    face
      .load()
      .then((loaded) => {
        if (!cancelled) {
          (document.fonts as unknown as {add: (face: FontFace) => void}).add(loaded);
        }
      })
      .catch((error) => {
        console.error('PromoSC font failed to load', error);
      })
      .finally(() => {
        continueRender(handle);
      });
    return () => {
      cancelled = true;
    };
  }, [handle]);
};
