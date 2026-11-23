import React from 'react';
import {useCurrentFrame} from 'remotion';
import {SceneShell} from '../components/SceneShell';
import {Eyebrow, Lead, Rule, SceneTitle, Tag} from '../components/Reveal';
import {ScreenDeck} from '../components/ScreenDeck';
import {PlainShot, SourceNote} from '../components/Capture';
import {WeekWidget} from '../components/WidgetArt';
import {FeatureRow, Panel, SectionLabel} from '../components/Blocks';
import {Glyph} from '../components/Glyph';
import {C, FONT} from '../theme';
import {il} from '../lib/anim';

/** 02:35–02:50 · 桌面组件：本周课表 4×4，今日、余额、电量与待办的裁图。 */
export const Scene12: React.FC<{duration: number}> = ({duration}) => {
  const frame = useCurrentFrame();
  const f1 = il(frame, [0, 14], [0, 1]) * il(frame, [258, 278], [1, 0]);
  const f2 = il(frame, [258, 278], [0, 1]);
  const phase1 = il(frame, [0, 14], [0, 1]) * il(frame, [250, 272], [1, 0]);
  const phase2 = il(frame, [272, 296], [0, 1]);
  return (
    <SceneShell duration={duration}>
      <div style={{position: 'absolute', left: 120, top: 138}}>
        <Eyebrow index="12" text="桌面组件" start={0} />
      </div>
      <div style={{position: 'absolute', left: 116, top: 198}}>
        <SceneTitle lines={['把课表，', '留在桌面。']} start={4} size={88} />
      </div>
      <div style={{position: 'absolute', left: 120, top: 392}}>
        <Lead start={14} style={{maxWidth: 690}}>
          本周课表从周日排到周六。
          <br />
          点一下，回到完整课表。
        </Lead>
      </div>
      <div style={{position: 'absolute', left: 120, top: 506}}>
        <Rule start={22} width={88} />
      </div>
      <div style={{position: 'absolute', left: 120, top: 548, display: 'flex', flexDirection: 'column', gap: 10}}>
        <FeatureRow
          icon="calendar"
          title="本周课表 · 4×4"
          sub="周日到周六；点开完整课表，沿用全局组件外观"
          n="01"
          at={20}
          active={f1}
          width={614}
        />
        <FeatureRow
          icon="grid"
          title="今日、余额、电量和待办"
          sub="四类小组件，可选独立静态磨砂玻璃外观"
          n="02"
          at={262}
          active={f2}
          width={614}
          tone="blue"
        />
      </div>
      <div style={{position: 'absolute', left: 120, top: 722, display: 'flex', gap: 8, flexWrap: 'wrap', width: 760}}>
        <Tag start={30} tone="green" icon={<Glyph name="grid" size={15} color={C.brand} />}>
          系统桌面
        </Tag>
        <Tag start={194} tone="plain">
          点开完整课表
        </Tag>
        <Tag start={276} tone="plain">
          玻璃可选
        </Tag>
        <Tag start={286} tone="warn">
          合成课表示意
        </Tag>
      </div>
      <div style={{position: 'absolute', left: 120, top: 778}}>
        <SourceNote>真实桌面未绑定学校账号；七日布局为明确标注的合成示意。</SourceNote>
      </div>

      <div style={{position: 'absolute', left: 1440, top: 34, opacity: phase1}}>
        <ScreenDeck
          phoneWidth={330}
          glow="rgba(73,93,146,0.14)"
          screens={[
            {id: 'widgets', at: 0},
            {id: 'widget-week-live', at: 52},
            {id: 'widget-todo-live', at: 302},
          ]}
        />
        <div
          style={{
            marginTop: 10,
            width: 340,
            fontFamily: FONT.cjk,
            fontSize: 12,
            color: C.ink3,
            textAlign: 'center',
          }}
        >
          真实桌面 · 未绑定账号
        </div>
      </div>

      <div style={{position: 'absolute', left: 930, top: 118, opacity: phase1}}>
        <div style={{display: 'flex', alignItems: 'center', gap: 8}}>
          <SectionLabel at={56}>七日布局示意 · 合成课程</SectionLabel>
          <Glyph name="info" size={14} color={C.ink3} />
        </div>
        <div style={{marginTop: 10}}>
          <WeekWidget size={382} />
        </div>
        <div
          style={{
            marginTop: 10,
            fontFamily: FONT.cjk,
            fontSize: 12,
            color: C.ink3,
            maxWidth: 382,
            lineHeight: 1.5,
          }}
        >
          真实组件在未绑定账号时显示空状态；此处用合成课表示意七日结构。
        </div>
      </div>

      <div style={{position: 'absolute', left: 930, top: 110, opacity: phase2}}>
        <SectionLabel at={278}>四类小组件 · 真实界面 / 演示数据</SectionLabel>
        <div style={{marginTop: 12}}>
          <PlainShot id="widget-today" width={462} radius={20} shadow />
        </div>
        <div style={{display: 'flex', gap: 16, marginTop: 18}}>
          <PlainShot id="widget-card" width={198} radius={20} shadow />
          <PlainShot id="widget-power" width={198} radius={20} shadow />
          <PlainShot id="widget-todo" width={158} radius={20} shadow />
        </div>
        <div
          style={{
            marginTop: 14,
            fontFamily: FONT.cjk,
            fontSize: 12,
            color: C.ink3,
            maxWidth: 620,
            lineHeight: 1.5,
          }}
        >
          界面为真实桌面；课程、余额、电量与待办数值均为演示数据。
        </div>
      </div>

      <Panel width={252} at={286} style={{position: 'absolute', left: 1570, top: 388, opacity: phase2}} tone="green">
        <SectionLabel at={290}>玻璃外观</SectionLabel>
        <div style={{fontFamily: FONT.cjk, fontSize: 13, lineHeight: 1.6, color: C.ink2, marginTop: 6}}>
          今日课表、校园卡、电量、待办可选独立静态玻璃；
          本周课表沿用全局组件外观。
        </div>
      </Panel>

    </SceneShell>
  );
};
