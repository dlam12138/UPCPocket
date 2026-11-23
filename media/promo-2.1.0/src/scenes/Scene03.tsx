import React from 'react';
import {useCurrentFrame} from 'remotion';
import {SceneShell} from '../components/SceneShell';
import {Eyebrow, Lead, Rise, SceneTitle, Tag, Rule} from '../components/Reveal';
import {ScreenDeck} from '../components/ScreenDeck';
import {SourceNote} from '../components/Capture';
import {Glyph, GlyphName} from '../components/Glyph';
import {C, FONT} from '../theme';
import {il} from '../lib/anim';

const ModeCard: React.FC<{
  icon: GlyphName;
  label: string;
  time: string;
  distance: string;
  active: number;
  at: number;
}> = ({icon, label, time, distance, active, at}) => {
  const frame = useCurrentFrame();
  const p = il(frame, [at, at + 22], [0, 1]);
  const a = Math.min(1, Math.max(0, active));
  return (
    <div
      style={{
        width: 270,
        borderRadius: 18,
        padding: '16px 18px',
        background: `rgba(255,255,255,${0.44 + a * 0.3})`,
        border: `1px solid ${a > 0.4 ? 'rgba(27,107,74,0.36)' : C.line}`,
        boxShadow: a > 0.4 ? '0 16px 40px rgba(27,107,74,0.12)' : 'none',
        opacity: p,
        transform: `translateY(${(1 - p) * 18}px) scale(${0.98 + a * 0.02})`,
      }}
    >
      <div style={{display: 'flex', alignItems: 'center', gap: 9}}>
        <div
          style={{
            width: 34,
            height: 34,
            borderRadius: 999,
            background: a > 0.4 ? C.brand : C.brandSoft,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <Glyph name={icon} size={18} color={a > 0.4 ? '#fff' : C.brand} />
        </div>
        <div style={{fontFamily: FONT.cjk, fontSize: 19, fontWeight: 650, color: C.ink}}>
          {label}
        </div>
      </div>
      <div style={{display: 'flex', alignItems: 'flex-end', gap: 6, marginTop: 12}}>
        <div style={{fontFamily: FONT.cjk, fontSize: 34, fontWeight: 800, color: C.ink}}>{time}</div>
        <div style={{fontFamily: FONT.cjk, fontSize: 15, color: C.ink3, paddingBottom: 5}}>分钟</div>
      </div>
      <div style={{fontFamily: FONT.cjk, fontSize: 14, color: C.ink3, marginTop: 2}}>{distance}</div>
    </div>
  );
};

const NavSketch: React.FC<{at: number; out: number}> = ({at, out}) => {
  const frame = useCurrentFrame();
  const p = il(frame, [at, at + 24], [0, 1]);
  const o = il(frame, [out, out + 18], [1, 0]);
  return (
    <div
      style={{
        position: 'absolute',
        left: 780,
        top: 610,
        width: 338,
        padding: '16px 18px',
        borderRadius: 18,
        background: 'rgba(13, 38, 28, 0.88)',
        color: '#EAF4ED',
        boxShadow: '0 22px 48px rgba(8,24,16,0.28)',
        opacity: p * o,
        transform: `translateY(${(1 - p) * 20}px)`,
      }}
    >
      <div style={{display: 'flex', alignItems: 'center', gap: 10}}>
        <Glyph name="navigate" size={20} color="#8FD4B0" />
        <div style={{fontFamily: FONT.cjk, fontSize: 17, fontWeight: 650}}>前台定位 · 导航示意</div>
      </div>
      <div style={{display: 'flex', alignItems: 'center', gap: 14, marginTop: 12}}>
        <svg width="94" height="54" viewBox="0 0 94 54" fill="none">
          <path d="M8 46h22c12 0 12-16 24-16h14" stroke="rgba(255,255,255,0.28)" strokeWidth="6" strokeLinecap="round" />
          <path d="M8 46h22c12 0 12-16 24-16h14" stroke="#8FD4B0" strokeWidth="4" strokeLinecap="round" strokeDasharray="6 8" />
          <path d="m62 22 12 8-12 8" stroke="#8FD4B0" strokeWidth="4" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
        <div style={{fontFamily: FONT.cjk, fontSize: 14, lineHeight: 1.6, color: 'rgba(255,255,255,0.6)'}}>
          当前位置与转向提示
          <br />
          确认走偏后重新规划
        </div>
      </div>
    </div>
  );
};

/** 00:21–00:40 · 认路：步行 / 骑行比较、路线预览与校区范围说明。 */
export const Scene03: React.FC<{duration: number}> = ({duration}) => {
  const frame = useCurrentFrame();
  const walkActive = il(frame, [0, 18], [0, 1]) * il(frame, [154, 178], [1, 0]);
  const bikeActive = il(frame, [154, 178], [0, 1]) * il(frame, [388, 410], [1, 0]);
  const guz = il(frame, [388, 412], [0, 1]);
  return (
    <SceneShell duration={duration}>
      <div style={{position: 'absolute', left: 120, top: 138}}>
        <Eyebrow index="03" text="认路" start={0} />
      </div>
      <div style={{position: 'absolute', left: 116, top: 196}}>
        <SceneTitle lines={['先认路，', '再出发。']} start={4} size={92} />
      </div>
      <div style={{position: 'absolute', left: 120, top: 404}}>
        <Lead start={12} style={{maxWidth: 660}}>
          唐岛湾南门 → 图书馆
          <br />
          同一起终点，换一种走法。
        </Lead>
      </div>
      <div style={{position: 'absolute', left: 120, top: 506}}>
        <Rule start={20} width={88} />
      </div>
      <div style={{position: 'absolute', left: 120, top: 548, display: 'flex', gap: 12}}>
        <ModeCard
          icon="walk"
          label="步行"
          time="8"
          distance="627 米 · 最快"
          active={walkActive}
          at={22}
        />
        <ModeCard
          icon="bike"
          label="骑行"
          time="3"
          distance="633 米 · 最快"
          active={bikeActive}
          at={32}
        />
      </div>
      <div style={{position: 'absolute', left: 120, top: 730, display: 'flex', gap: 8, flexWrap: 'wrap', width: 700}}>
        <Tag start={46} tone="green" icon={<Glyph name="navigate" size={15} color={C.brand} />}>
          前台定位
        </Tag>
        <Tag start={54} tone="plain">
          转向提示
        </Tag>
        <Tag start={62} tone="plain">
          偏航重算
        </Tag>
        <Tag start={398} tone="warn">
          古镇口西区 · 主要道路待补
        </Tag>
      </div>
      <div style={{position: 'absolute', left: 120, top: 790}}>
        <SourceNote>路线为预览；前台导航为示意，未进行实地导航实测。</SourceNote>
        <SourceNote style={{marginTop: 7}}>地图：© OpenStreetMap contributors</SourceNote>
      </div>

      <div style={{position: 'absolute', left: 1220, top: 18}}>
        <ScreenDeck
          phoneWidth={442}
          glow="rgba(73,93,146,0.18)"
          screens={[
            {id: 'map-origin-wide', at: 0},
            {id: 'map-origin', at: 56},
            {id: 'map-walk', at: 106},
            {id: 'map-bike', at: 158},
            {id: 'map-point', at: 300},
            {id: 'map-guzhenkou', at: 390},
          ]}
        />
      </div>
      <NavSketch at={210} out={386} />
      <div
        style={{
          position: 'absolute',
          left: 1708,
          top: 150,
          width: 172,
          fontFamily: FONT.cjk,
          fontSize: 14,
          lineHeight: 1.6,
          color: C.ink3,
        }}
      >
        <div style={{color: guz > 0.4 ? C.amber : C.ink3}}>
          {guz > 0.4 ? '西区以地点浏览为主' : '两校区可切换'}
        </div>
      </div>
      <div
        style={{
          position: 'absolute',
          left: 1220,
          top: 1000,
          fontFamily: FONT.mono,
          fontSize: 12,
          color: C.ink3,
          opacity: il(frame, [30, 52], [0, 1]),
        }}
      >
        OFFLINE MAP · 2026
      </div>
    </SceneShell>
  );
};
