import React from 'react';
import {useCurrentFrame} from 'remotion';
import {SceneShell} from '../components/SceneShell';
import {Eyebrow, Lead, Rule, SceneTitle, Tag} from '../components/Reveal';
import {ScreenDeck} from '../components/ScreenDeck';
import {SourceNote} from '../components/Capture';
import {CheckLine, FeatureRow, Panel, SectionLabel, StatTile} from '../components/Blocks';
import {Glyph} from '../components/Glyph';
import {C, FONT} from '../theme';
import {il} from '../lib/anim';

/** 02:16–02:35 · 晚自习：截止与提醒、待办位置、番茄钟与本机统计。 */
export const Scene11: React.FC<{duration: number}> = ({duration}) => {
  const frame = useCurrentFrame();
  const f1 = il(frame, [0, 14], [0, 1]) * il(frame, [198, 218], [1, 0]);
  const f2 = il(frame, [198, 218], [0, 1]) * il(frame, [328, 348], [1, 0]);
  const f3 = il(frame, [328, 348], [0, 1]);
  const done = il(frame, [356, 380], [0, 1]);
  return (
    <SceneShell duration={duration}>
      <div style={{position: 'absolute', left: 120, top: 138}}>
        <Eyebrow index="11" text="晚自习" start={0} />
      </div>
      <div style={{position: 'absolute', left: 116, top: 198}}>
        <SceneTitle lines={['晚自习，', '把事做完。']} start={4} size={88} />
      </div>
      <div style={{position: 'absolute', left: 120, top: 392}}>
        <Lead start={14} style={{maxWidth: 680}}>
          早上记下的高数笔记，晚上接着做。
          <br />
          截止、提醒和专注，都留在本机。
        </Lead>
      </div>
      <div style={{position: 'absolute', left: 120, top: 506}}>
        <Rule start={22} width={88} />
      </div>
      <div style={{position: 'absolute', left: 120, top: 548, display: 'flex', flexDirection: 'column', gap: 10}}>
        <FeatureRow
          icon="checkCircle"
          title="截止与一次提醒分开设"
          sub="待办独立保存；提醒不依赖日程事件"
          n="01"
          at={20}
          active={f1}
          width={614}
        />
        <FeatureRow
          icon="layers"
          title="待办位置可切换"
          sub="课表页顶部，或专注页计时下方"
          n="02"
          at={202}
          active={f2}
          width={614}
          tone="blue"
        />
        <FeatureRow
          icon="timer"
          title="番茄钟与本机统计"
          sub="完成一轮，今天学了多久都能回看"
          n="03"
          at={332}
          active={f3}
          width={614}
        />
      </div>
      <div style={{position: 'absolute', left: 120, top: 796, display: 'flex', gap: 8, flexWrap: 'wrap', width: 750}}>
        <Tag start={30} tone="green" icon={<Glyph name="bell" size={15} color={C.brand} />}>
          一次性提醒
        </Tag>
        <Tag start={210} tone="plain">
          位置可切换
        </Tag>
        <Tag start={340} tone="plain">
          统计留本机
        </Tag>
        <Tag start={352} tone="warn">
          不导出
        </Tag>
      </div>
      <div style={{position: 'absolute', left: 120, top: 846}}>
        <SourceNote>待办与计时为本机合成数据；统计页为真实界面，显示 25 分钟 / 1 个番茄。</SourceNote>
      </div>

      <div style={{position: 'absolute', left: 1290, top: 26}}>
        <ScreenDeck
          phoneWidth={420}
          glow="rgba(73,93,146,0.16)"
          screens={[
            {id: 'todo', at: 0},
            {id: 'todo-edit', at: 94},
            {id: 'todo-position', at: 200},
            {id: 'focus-todo', at: 294},
            {id: 'focus-active', at: 372},
            {id: 'focus-stats', at: 462},
          ]}
        />
      </div>

      <Panel width={286} at={98} out={202} style={{position: 'absolute', left: 934, top: 188}} tone="paper">
        <SectionLabel at={102}>截止与提醒 · 分别设置</SectionLabel>
        <div style={{display: 'flex', flexDirection: 'column', gap: 9, marginTop: 10}}>
          <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center', paddingTop: 8, borderTop: `1px solid ${C.line}`}}>
            <div style={{fontFamily: FONT.cjk, fontSize: 14, color: C.ink}}>截止</div>
            <div style={{fontFamily: FONT.cjk, fontSize: 12, color: C.ink3}}>可选日期与时间</div>
          </div>
          <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center'}}>
            <div style={{fontFamily: FONT.cjk, fontSize: 14, color: C.ink}}>提醒</div>
            <div style={{fontFamily: FONT.cjk, fontSize: 12, color: C.ink3}}>一次，可关闭</div>
          </div>
        </div>
        <div style={{fontFamily: FONT.cjk, fontSize: 12, lineHeight: 1.55, color: C.ink3, marginTop: 10}}>
          两项互不依赖；不设截止也能单独提醒。
        </div>
      </Panel>

      <Panel width={286} at={206} out={332} style={{position: 'absolute', left: 934, top: 188}} tone="blue">
        <SectionLabel at={210}>日程待办位置</SectionLabel>
        <div style={{display: 'flex', flexDirection: 'column', gap: 7, marginTop: 10}}>
          {['课表页顶部', '专注页计时下方'].map((t, i) => (
            <div
              key={t}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 9,
                padding: '8px 10px',
                borderRadius: 10,
                background: i === 1 ? 'rgba(255,255,255,0.78)' : 'rgba(255,255,255,0.36)',
                border: `1px solid ${i === 1 ? 'rgba(73,93,146,0.4)' : C.line}`,
              }}
            >
              <div
                style={{
                  width: 13,
                  height: 13,
                  borderRadius: 999,
                  border: `1.4px solid ${i === 1 ? C.blue : C.lineStrong}`,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                {i === 1 ? <div style={{width: 6, height: 6, borderRadius: 999, background: C.blue}} /> : null}
              </div>
              <div style={{fontFamily: FONT.cjk, fontSize: 13, color: C.ink2}}>{t}</div>
            </div>
          ))}
        </div>
        <div style={{fontFamily: FONT.cjk, fontSize: 11, color: C.ink3, marginTop: 9}}>
          设置 → 应用 → 日程待办位置
        </div>
      </Panel>

      <Panel width={286} at={336} style={{position: 'absolute', left: 934, top: 188}} tone="green">
        <SectionLabel at={340}>完成与统计</SectionLabel>
        <div style={{marginTop: 10}}>
          <CheckLine
            text="整理高等数学笔记"
            at={344}
            checked={done}
            width={246}
            note="勾选示意"
          />
        </div>
        <div style={{display: 'flex', gap: 8, marginTop: 10}}>
          <StatTile value="25" unit="分钟" label="累计专注" at={362} width={114} />
          <StatTile value="1" unit="个" label="累计番茄" at={372} width={114} tone="blue" />
        </div>
        <div style={{fontFamily: FONT.cjk, fontSize: 11, color: C.ink3, marginTop: 9, lineHeight: 1.5}}>
          25 分钟与 1 个番茄来自真实统计页。
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
        待办与专注 · 本机数据
      </div>
    </SceneShell>
  );
};
