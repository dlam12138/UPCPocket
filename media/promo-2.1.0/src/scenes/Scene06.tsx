import React from 'react';
import {useCurrentFrame} from 'remotion';
import {SceneShell} from '../components/SceneShell';
import {Eyebrow, Lead, Rule, SceneTitle, Tag} from '../components/Reveal';
import {ScreenDeck} from '../components/ScreenDeck';
import {SourceNote} from '../components/Capture';
import {Panel, SectionLabel, Step} from '../components/Blocks';
import {Glyph} from '../components/Glyph';
import {C, FONT} from '../theme';
import {il} from '../lib/anim';

/** 01:05–01:17 · 找自习室：校区、日期、快捷时段与具体节次。 */
export const Scene06: React.FC<{duration: number}> = ({duration}) => {
  const frame = useCurrentFrame();
  const s1 = il(frame, [0, 12], [0, 1]) * il(frame, [80, 98], [1, 0]);
  const s2 = il(frame, [80, 98], [0, 1]) * il(frame, [180, 198], [1, 0]);
  const s3 = il(frame, [180, 198], [0, 1]);
  const guz = il(frame, [282, 304], [0, 1]);
  return (
    <SceneShell duration={duration}>
      <div style={{position: 'absolute', left: 120, top: 138}}>
        <Eyebrow index="06" text="找自习室" start={0} />
      </div>
      <div style={{position: 'absolute', left: 116, top: 198}}>
        <SceneTitle lines={['下午，找间', '空教室。']} start={4} size={90} />
      </div>
      <div style={{position: 'absolute', left: 120, top: 404}}>
        <Lead start={14} style={{maxWidth: 660}}>
          先选校区和日期，再按快捷时段查。
          <br />
          想挑具体哪几节，也可以自己勾。
        </Lead>
      </div>
      <div style={{position: 'absolute', left: 120, top: 520}}>
        <Rule start={22} width={88} />
      </div>
      <div style={{position: 'absolute', left: 120, top: 560, display: 'flex', flexDirection: 'column', gap: 20}}>
        <Step n="1" title="校区与日期" sub="唐岛湾 / 古镇口，日期可前后切换" at={24} active={s1} width={610} />
        <Step n="2" title="点「下午」快捷查询" sub="上午、下午、晚上各自对应节次" at={82} active={s2} width={610} />
        <Step n="3" title="具体节次再筛选" sub="选教学楼与第 1–13 节，应用后查询" at={182} active={s3} width={610} last />
      </div>
      <div style={{position: 'absolute', left: 120, top: 760, display: 'flex', gap: 8, flexWrap: 'wrap', width: 720}}>
        <Tag start={42} tone="green" icon={<Glyph name="classroom" size={15} color={C.brand} />}>
          按日期与节次
        </Tag>
        <Tag start={50} tone="plain">
          只看空闲
        </Tag>
        <Tag start={58} tone="plain">
          教学楼分组
        </Tag>
      </div>
      <div style={{position: 'absolute', left: 120, top: 812}}>
        <SourceNote>查询结果为合成演示；入口、筛选与两校区切换为真实界面。</SourceNote>
      </div>

      <div style={{position: 'absolute', left: 1290, top: 26}}>
        <ScreenDeck
          phoneWidth={424}
          glow="rgba(73,93,146,0.16)"
          screens={[
            {id: 'classroom', at: 0},
            {id: 'classroom-afternoon', at: 74},
            {id: 'classroom-filter', at: 180},
            {id: 'classroom-guzhenkou', at: 286},
          ]}
        />
      </div>
      <Panel width={272} at={80} out={178} style={{position: 'absolute', left: 936, top: 206}} tone="green">
        <SectionLabel at={84}>唐岛湾 · 下午</SectionLabel>
        <div style={{display: 'flex', alignItems: 'flex-end', gap: 8, marginTop: 8}}>
          <div style={{fontFamily: FONT.cjk, fontSize: 42, fontWeight: 800, color: C.brand, lineHeight: 1}}>
            6
          </div>
          <div style={{fontFamily: FONT.cjk, fontSize: 15, color: C.ink3, paddingBottom: 4}}>
            / 共 8 间空闲
          </div>
        </div>
        <div style={{fontFamily: FONT.cjk, fontSize: 13, color: C.ink3, marginTop: 7}}>
          南教 101、102、104 等
        </div>
      </Panel>
      <Panel width={272} at={186} out={282} style={{position: 'absolute', left: 936, top: 206}} tone="blue">
        <SectionLabel at={190}>详细筛选</SectionLabel>
        <div style={{fontFamily: FONT.cjk, fontSize: 15, lineHeight: 1.6, color: C.ink2, marginTop: 7}}>
          教学楼、1–13 节自选；全选或清空后再应用。
        </div>
      </Panel>
      <Panel width={272} at={290} style={{position: 'absolute', left: 936, top: 206}} tone={guz > 0.4 ? 'paper' : 'green'}>
        <SectionLabel at={294}>古镇口 · 卓越教学楼</SectionLabel>
        <div style={{fontFamily: FONT.cjk, fontSize: 15, lineHeight: 1.7, color: C.ink2, marginTop: 7}}>
          卓越 A000 空闲
          <br />
          A001、B000 占用
        </div>
        <div style={{fontFamily: FONT.cjk, fontSize: 12, color: C.ink3, marginTop: 6}}>
          结果随时间与查询条件变化
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
        空闲教室 · 真实筛选界面
      </div>
    </SceneShell>
  );
};
