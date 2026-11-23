import React from 'react';
import {useCurrentFrame} from 'remotion';
import {SceneShell} from '../components/SceneShell';
import {Eyebrow, Lead, Rise, SceneTitle, Tag} from '../components/Reveal';
import {ScreenDeck} from '../components/ScreenDeck';
import {NoteRail} from '../components/Callout';
import {SourceNote} from '../components/Capture';
import {DemoBadge} from '../components/Blocks';
import {Glyph, GlyphName} from '../components/Glyph';
import {C, FONT} from '../theme';
import {il} from '../lib/anim';

const Feature: React.FC<{n: string; icon: GlyphName; title: string; sub: string; at: number}> = ({
  n,
  icon,
  title,
  sub,
  at,
}) => {
  const frame = useCurrentFrame();
  const p = il(frame, [at, at + 22], [0, 1]);
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 16,
        width: 620,
        padding: '14px 16px',
        borderRadius: 16,
        background: 'rgba(255,255,255,0.44)',
        border: `1px solid ${C.line}`,
        opacity: p,
        transform: `translateX(${(1 - p) * 18}px)`,
      }}
    >
      <div
        style={{
          width: 42,
          height: 42,
          borderRadius: 13,
          background: C.brandSoft,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          flex: 'none',
        }}
      >
        <Glyph name={icon} size={22} color={C.brand} />
      </div>
      <div style={{flex: 1}}>
        <div style={{fontFamily: FONT.cjk, fontSize: 21, fontWeight: 650, color: C.ink}}>{title}</div>
        <div style={{fontFamily: FONT.cjk, fontSize: 16, color: C.ink3, marginTop: 2}}>{sub}</div>
      </div>
      <div style={{fontFamily: FONT.mono, fontSize: 13, color: C.ink3, letterSpacing: 1}}>{n}</div>
    </div>
  );
};

/** 00:08–00:21 · 看课：今日、整周、详情与离线。 */
export const Scene02: React.FC<{duration: number}> = ({duration}) => {
  const frame = useCurrentFrame();
  return (
    <SceneShell duration={duration}>
      <div style={{position: 'absolute', left: 120, top: 148}}>
        <Eyebrow index="02" text="看课" start={0} />
      </div>
      <div style={{position: 'absolute', left: 116, top: 208}}>
        <SceneTitle lines={['一周的课，', '一眼看清。']} start={4} size={88} />
      </div>
      <div style={{position: 'absolute', left: 120, top: 420}}>
        <Lead start={12} style={{maxWidth: 700}}>
          首页看今天，课表里看一周。
          <br />
          教室、节次和上课时间，都在课程详情里。
        </Lead>
      </div>
      <div style={{position: 'absolute', left: 120, top: 552, display: 'flex', flexDirection: 'column', gap: 10}}>
        <Feature n="01" icon="calendar" title="今日课程" sub="下一堂与上课地点" at={22} />
        <Feature n="02" icon="classroom" title="整周课表" sub="周日到周六，节次排布" at={38} />
        <Feature n="03" icon="receipt" title="已查周次可离线翻看" sub="缓存留在本机" at={230} />
      </div>
      <div style={{position: 'absolute', left: 120, top: 790}}>
        <SourceNote>课表为只读视图，不提供编辑、导出或课程提醒。</SourceNote>
      </div>

      <div style={{position: 'absolute', left: 1272, top: 26}}>
        <ScreenDeck
          phoneWidth={424}
          glow="rgba(73,93,146,0.16)"
          screens={[
            {id: 'home-demo', at: 0},
            {id: 'schedule-demo', at: 22},
            {id: 'course-detail', at: 230},
          ]}
        />
      </div>
      <DemoBadge left={1628} top={958} at={24} />
      <NoteRail
        x={930}
        y={166}
        width={286}
        start={30}
        items={[
          {title: '整周', sub: '周日 → 周六'},
          {title: '一节课', sub: '地点 · 节次 · 时间'},
          {title: '离线', sub: '查过的那一周'},
        ]}
      />
      <div
        style={{
          position: 'absolute',
          left: 1268,
          top: 982,
          fontFamily: FONT.cjk,
          fontSize: 13,
          color: C.ink3,
          opacity: il(frame, [40, 64], [0, 1]),
        }}
      >
        当前宿主四项导航 · 课表页
      </div>
      <Rise start={248} style={{position: 'absolute', left: 770, top: 850}}>
        <Tag start={248} tone="green" icon={<Glyph name="checkCircle" size={16} color={C.brand} />}>
          已查周次 · 离线可翻
        </Tag>
      </Rise>
    </SceneShell>
  );
};
