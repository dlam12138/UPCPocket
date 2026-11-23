import React from 'react';
import {useCurrentFrame} from 'remotion';
import {SceneShell} from '../components/SceneShell';
import {Eyebrow, Lead, Rule, SceneTitle, Tag} from '../components/Reveal';
import {ScreenDeck} from '../components/ScreenDeck';
import {SourceNote} from '../components/Capture';
import {FeatureRow, Panel, SectionLabel, StatTile} from '../components/Blocks';
import {Glyph} from '../components/Glyph';
import {C, FONT} from '../theme';
import {il} from '../lib/anim';

/** 01:34–01:47 · 看活动、查事项：石光与 e站通都只读，结果以学校页面为准。 */
export const Scene08: React.FC<{duration: number}> = ({duration}) => {
  const frame = useCurrentFrame();
  const f1 = il(frame, [0, 14], [0, 1]) * il(frame, [162, 182], [1, 0]);
  const f2 = il(frame, [162, 182], [0, 1]) * il(frame, [286, 306], [1, 0]);
  const f3 = il(frame, [286, 306], [0, 1]);
  return (
    <SceneShell duration={duration}>
      <div style={{position: 'absolute', left: 120, top: 138}}>
        <Eyebrow index="08" text="活动与事项" start={0} />
      </div>
      <div style={{position: 'absolute', left: 116, top: 198}}>
        <SceneTitle lines={['活动与事项，', '都在手边。']} start={4} size={86} />
      </div>
      <div style={{position: 'absolute', left: 120, top: 392}}>
        <Lead start={14} style={{maxWidth: 680}}>
          石光看活动、学分学时与本人参与；
          <br />
          e站通查事项指南与待办。都只读。
        </Lead>
      </div>
      <div style={{position: 'absolute', left: 120, top: 506}}>
        <Rule start={22} width={88} />
      </div>
      <div style={{position: 'absolute', left: 120, top: 548, display: 'flex', flexDirection: 'column', gap: 10}}>
        <FeatureRow
          icon="event"
          title="石光 · 活动与学分学时"
          sub="活动详情、学分学时与本人参与记录"
          n="01"
          at={20}
          active={f1}
          width={614}
        />
        <FeatureRow
          icon="list"
          title="e站通 · 事项指南与待办"
          sub="事项说明、在办事项，按页面结果查看"
          n="02"
          at={166}
          active={f2}
          width={614}
          tone="blue"
        />
        <FeatureRow
          icon="lock"
          title="只读，不代报名"
          sub="不在应用内报名、签到或提交事项"
          n="03"
          at={290}
          active={f3}
          width={614}
        />
      </div>
      <div style={{position: 'absolute', left: 120, top: 790, display: 'flex', gap: 8, flexWrap: 'wrap', width: 740}}>
        <Tag start={30} tone="green" icon={<Glyph name="lock" size={15} color={C.brand} />}>
          只读访问
        </Tag>
        <Tag start={178} tone="plain">
          页面结果为准
        </Tag>
        <Tag start={300} tone="warn">
          失败不伪装成空结果
        </Tag>
      </div>
      <div style={{position: 'absolute', left: 120, top: 842}}>
        <SourceNote>石光数据为合成演示；e站通保留真实离线与加载失败提示。截图不展示完整个人信息。</SourceNote>
      </div>

      <div style={{position: 'absolute', left: 1290, top: 26}}>
        <ScreenDeck
          phoneWidth={420}
          glow="rgba(73,93,146,0.16)"
          screens={[
            {id: 'shiguang', at: 0},
            {id: 'shiguang-detail', at: 64},
            {id: 'shiguang-mine', at: 122},
            {id: 'estation', at: 168},
          ]}
        />
      </div>

      <Panel width={286} at={30} out={166} style={{position: 'absolute', left: 934, top: 188}} tone="paper">
        <SectionLabel at={34}>石光 · 学分与学时</SectionLabel>
        <div style={{display: 'flex', gap: 8, marginTop: 12}}>
          <StatTile value="1.5" unit="学分" label="本次活动" at={42} width={114} />
          <StatTile value="24" unit="学时" label="累计示意" at={50} width={114} tone="blue" />
        </div>
        <div style={{fontFamily: FONT.cjk, fontSize: 12, lineHeight: 1.55, color: C.ink3, marginTop: 10}}>
          本人参与记录按学校页面展示；合成数据在界面内已标注。
        </div>
      </Panel>

      <Panel width={286} at={170} style={{position: 'absolute', left: 934, top: 188}} tone="green">
        <SectionLabel at={174}>e站通 · 事项与待办</SectionLabel>
        <div style={{display: 'flex', flexDirection: 'column', gap: 9, marginTop: 10}}>
          {[
            ['事项指南', '办理流程与材料说明'],
            ['待办 / 在办', '当前状态与入口'],
          ].map(([a, b]) => (
            <div key={a} style={{paddingTop: 8, borderTop: `1px solid ${C.line}`}}>
              <div style={{fontFamily: FONT.cjk, fontSize: 15, fontWeight: 650, color: C.ink}}>{a}</div>
              <div style={{fontFamily: FONT.cjk, fontSize: 12, color: C.ink3, marginTop: 2}}>{b}</div>
            </div>
          ))}
        </div>
        <div
          style={{
            marginTop: 10,
            display: 'flex',
            alignItems: 'center',
            gap: 7,
            fontFamily: FONT.cjk,
            fontSize: 12,
            color: C.amber,
          }}
        >
          <Glyph name="info" size={14} color={C.amber} />
          离线示例：保留网络失败提示
        </div>
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
        石光与 e站通 · 只读入口
      </div>
    </SceneShell>
  );
};
