import React from 'react';
import {useCurrentFrame} from 'remotion';
import {SceneShell} from '../components/SceneShell';
import {Eyebrow, Lead, Rule, SceneTitle, Tag} from '../components/Reveal';
import {ScreenDeck} from '../components/ScreenDeck';
import {SourceNote} from '../components/Capture';
import {FeatureRow, Panel, SectionLabel} from '../components/Blocks';
import {Glyph} from '../components/Glyph';
import {C, FONT} from '../theme';
import {il} from '../lib/anim';

const Legend: React.FC<{color: string; label: string}> = ({color, label}) => (
  <div style={{display: 'flex', alignItems: 'center', gap: 7}}>
    <div style={{width: 9, height: 9, borderRadius: 3, background: color}} />
    <div style={{fontFamily: FONT.cjk, fontSize: 13, color: C.ink3}}>{label}</div>
  </div>
);

const Records: React.FC = () => (
  <>
    <div style={{display: 'flex', alignItems: 'center', justifyContent: 'space-between'}}>
      <SectionLabel at={386}>预约记录 · 示意</SectionLabel>
      <Glyph name="receipt" size={17} color={C.ink3} />
    </div>
    {[
      ['视听鉴赏区 A01', '11-04 · 08:00–10:00'],
      ['临窗学习区 A12', '11-06 · 14:00–16:00'],
    ].map(([a, b]) => (
      <div
        key={a}
        style={{
          marginTop: 10,
          paddingTop: 10,
          borderTop: `1px solid ${C.line}`,
        }}
      >
        <div style={{fontFamily: FONT.cjk, fontSize: 14, fontWeight: 600, color: C.ink}}>{a}</div>
        <div style={{fontFamily: FONT.cjk, fontSize: 12, color: C.ink3, marginTop: 2}}>{b}</div>
      </div>
    ))}
    <div style={{fontFamily: FONT.cjk, fontSize: 11, color: C.ink3, marginTop: 10, lineHeight: 1.5}}>
      真实记录需学校账号；重新进入或点刷新会重读开放日期。
    </div>
  </>
);

/** 01:17–01:34 · 找座位：馆区、时段、座位图、本机隐藏与日期刷新。 */
export const Scene07: React.FC<{duration: number}> = ({duration}) => {
  const frame = useCurrentFrame();
  const f1 = il(frame, [0, 14], [0, 1]) * il(frame, [128, 148], [1, 0]);
  const f2 = il(frame, [128, 148], [0, 1]) * il(frame, [248, 268], [1, 0]);
  const f3 = il(frame, [248, 268], [0, 1]);
  return (
    <SceneShell duration={duration}>
      <div style={{position: 'absolute', left: 120, top: 138}}>
        <Eyebrow index="07" text="找座位" start={0} />
      </div>
      <div style={{position: 'absolute', left: 116, top: 198}}>
        <SceneTitle lines={['图书馆，', '先挑个座位。']} start={4} size={84} />
      </div>
      <div style={{position: 'absolute', left: 120, top: 392}}>
        <Lead start={14} style={{maxWidth: 670}}>
          先选馆区、日期与时段，再看座位图。
          <br />
          不常坐的位置，只在这台手机上隐藏。
        </Lead>
      </div>
      <div style={{position: 'absolute', left: 120, top: 508}}>
        <Rule start={22} width={88} />
      </div>
      <div style={{position: 'absolute', left: 120, top: 548, display: 'flex', flexDirection: 'column', gap: 9}}>
        <FeatureRow
          icon="building"
          title="馆区、楼层与开放日期"
          sub="三馆入口；日期来自学校当前开放"
          n="01"
          at={24}
          active={f1}
          width={610}
        />
        <FeatureRow
          icon="clock"
          title="时段与座位图"
          sub="可选、已用、暂离和不可选分开标记"
          n="02"
          at={132}
          active={f2}
          width={610}
          tone="blue"
        />
        <FeatureRow
          icon="seat"
          title="本机隐藏座位"
          sub="逐个或全部恢复，不影响他人"
          n="03"
          at={252}
          active={f3}
          width={610}
        />
      </div>
      <div style={{position: 'absolute', left: 120, top: 790, display: 'flex', gap: 8, flexWrap: 'wrap', width: 740}}>
        <Tag start={48} tone="green" icon={<Glyph name="seat" size={15} color={C.brand} />}>
          预约在本机确认
        </Tag>
        <Tag start={264} tone="plain">
          隐藏 ≠ 取消预约
        </Tag>
        <Tag start={396} tone="warn">
          刷新重读开放日期
        </Tag>
      </div>
      <div style={{position: 'absolute', left: 120, top: 842}}>
        <SourceNote>座位与余量为合成预览；应用内已明确标识，不生成真实预约。</SourceNote>
      </div>

      <div style={{position: 'absolute', left: 1290, top: 26}}>
        <ScreenDeck
          phoneWidth={420}
          glow="rgba(73,93,146,0.16)"
          screens={[
            {id: 'library', at: 0},
            {id: 'library-hub-guzhenkou', at: 62},
            {id: 'library-area', at: 116},
            {id: 'library-time', at: 170},
            {id: 'library-seat', at: 222},
            {id: 'library-hidden', at: 322},
            {id: 'library-area', at: 430},
          ]}
        />
      </div>

      <Panel width={286} at={104} out={272} style={{position: 'absolute', left: 934, top: 188}} tone="paper">
        <SectionLabel at={108}>时段与座位图</SectionLabel>
        <div style={{display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 10}}>
          <div style={{fontFamily: FONT.cjk, fontSize: 23, fontWeight: 750, color: C.ink}}>14:00–16:00</div>
          <div style={{fontFamily: FONT.cjk, fontSize: 12, color: C.ink3}}>2 小时</div>
        </div>
        <div style={{display: 'flex', flexWrap: 'wrap', gap: '8px 14px', marginTop: 12}}>
          <Legend color="#495D92" label="可用" />
          <Legend color="#C0392B" label="已约/使用" />
          <Legend color="#6B5B7B" label="暂离" />
          <Legend color="#B8B8B0" label="不可选" />
        </div>
        <div style={{fontFamily: FONT.cjk, fontSize: 12, color: C.ink3, marginTop: 10, lineHeight: 1.5}}>
          布局可横向、纵向滑动；点座位再确认。
        </div>
      </Panel>

      <Panel width={286} at={276} out={384} style={{position: 'absolute', left: 934, top: 188}} tone="green">
        <SectionLabel at={280}>本机隐藏 · 2 个</SectionLabel>
        <div style={{fontFamily: FONT.cjk, fontSize: 15, lineHeight: 1.7, color: C.ink2, marginTop: 8}}>
          隐藏只改变这台手机的座位列表；
          <br />
          不影响座位状态，也不会取消已有预约。
        </div>
        <div style={{display: 'flex', gap: 8, marginTop: 12}}>
          <Tag start={292} tone="green">
            逐个恢复
          </Tag>
          <Tag start={300} tone="plain">
            全部恢复
          </Tag>
        </div>
      </Panel>

      <Panel width={286} at={390} style={{position: 'absolute', left: 934, top: 188}} tone="paper">
        <Records />
      </Panel>

      <div
        style={{
          position: 'absolute',
          left: 1290,
          top: 1002,
          fontFamily: FONT.cjk,
          fontSize: 13,
          color: C.ink3,
          opacity: il(frame, [30, 54], [0, 1]),
        }}
      >
        图书馆座位 · 合成预览已标示
      </div>
    </SceneShell>
  );
};
