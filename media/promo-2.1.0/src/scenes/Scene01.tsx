import React from 'react';
import {useCurrentFrame} from 'remotion';
import {SceneShell} from '../components/SceneShell';
import {Eyebrow, Lead, Rise, Rule, SceneTitle, Tag} from '../components/Reveal';
import {PhoneFrame} from '../components/Phone';
import {Shot} from '../components/Capture';
import {NoteRail} from '../components/Callout';
import {DemoBadge} from '../components/Blocks';
import {C, FONT, SCREEN} from '../theme';
import {easeOut, il} from '../lib/anim';

/** 00:00–00:08 · 清晨：片名、首页与第一条待办。 */
export const Scene01: React.FC<{duration: number}> = ({duration}) => {
  const frame = useCurrentFrame();
  const phoneIn = il(frame, [0, 34], [0, 1], easeOut);
  return (
    <SceneShell duration={duration}>
      <div style={{position: 'absolute', left: 120, top: 158}}>
        <Eyebrow index="01" text="清晨" start={2} />
      </div>
      <div style={{position: 'absolute', left: 116, top: 226}}>
        <SceneTitle lines={['从早八，', '到晚自习。']} start={8} size={104} />
      </div>
      <div style={{position: 'absolute', left: 120, top: 520}}>
        <Lead start={28} style={{maxWidth: 820}}>
          一条待办，串起石大的一天。
        </Lead>
      </div>
      <div style={{position: 'absolute', left: 120, top: 596}}>
        <Rule start={44} width={120} />
      </div>
      <Rise start={56} style={{position: 'absolute', left: 120, top: 636}}>
        <div style={{display: 'flex', gap: 10}}>
          <Tag start={56} tone="green">
            2.1.0 功能预览
          </Tag>
          <Tag start={64} tone="plain">
            Android 12+
          </Tag>
        </div>
      </Rise>
      <div
        style={{
          position: 'absolute',
          left: 120,
          top: 760,
          width: 700,
          fontFamily: FONT.cjk,
          fontSize: 21,
          lineHeight: 1.7,
          color: C.ink3,
          opacity: il(frame, [84, 108], [0, 1]),
        }}
      >
        跟着一条待办，从早晨的教室走到晚上的书桌。
      </div>

      <div
        style={{
          position: 'absolute',
          left: 1318,
          top: 34,
          transform: `translateY(${(1 - phoneIn) * 34}px) scale(${0.96 + phoneIn * 0.04})`,
          opacity: phoneIn,
          transformOrigin: 'top center',
        }}
      >
        <PhoneFrame width={402} glow="rgba(27,107,74,0.20)">
          <Shot id="home-demo" width={SCREEN.w} />
        </PhoneFrame>
      </div>
      <DemoBadge left={1650} top={916} at={24} />
      <NoteRail
        x={900}
        y={206}
        width={296}
        start={52}
        items={[
          {title: '今日课程', sub: '高等数学 A · 南教 101'},
          {title: '一条待办', sub: '整理高等数学笔记'},
          {title: '当前宿主', sub: '首页 / 课表 / 服务 / 专注'},
        ]}
      />
      <div
        style={{
          position: 'absolute',
          left: 900,
          top: 520,
          fontFamily: FONT.mono,
          fontSize: 12,
          color: C.ink3,
          opacity: il(frame, [100, 124], [0, 1]),
        }}
      >
        2026 · AUTUMN
      </div>
    </SceneShell>
  );
};
