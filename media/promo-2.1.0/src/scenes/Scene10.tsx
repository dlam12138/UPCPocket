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

const HandoffSteps: React.FC<{items: string[]}> = ({items}) => (
  <div style={{display: 'flex', flexDirection: 'column', gap: 7, marginTop: 9}}>
    {items.map((t, i) => (
      <div key={t} style={{display: 'flex', alignItems: 'center', gap: 9}}>
        <div
          style={{
            width: 19,
            height: 19,
            borderRadius: 999,
            border: `1px solid ${C.lineStrong}`,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontFamily: FONT.mono,
            fontSize: 10,
            color: C.ink3,
            flex: 'none',
          }}
        >
          {i + 1}
        </div>
        <div style={{fontFamily: FONT.cjk, fontSize: 13, color: C.ink2}}>{t}</div>
      </div>
    ))}
  </div>
);

/** 02:04–02:16 · 下课运动：体育场馆与泳馆的微信入口交接，不代预约。 */
export const Scene10: React.FC<{duration: number}> = ({duration}) => {
  const frame = useCurrentFrame();
  const f1 = il(frame, [0, 14], [0, 1]) * il(frame, [148, 168], [1, 0]);
  const f2 = il(frame, [148, 168], [0, 1]) * il(frame, [256, 276], [1, 0]);
  const f3 = il(frame, [256, 276], [0, 1]);
  return (
    <SceneShell duration={duration}>
      <div style={{position: 'absolute', left: 120, top: 138}}>
        <Eyebrow index="10" text="下课运动" start={0} />
      </div>
      <div style={{position: 'absolute', left: 116, top: 198}}>
        <SceneTitle lines={['下课，', '去运动。']} start={4} size={92} />
      </div>
      <div style={{position: 'absolute', left: 120, top: 396}}>
        <Lead start={14} style={{maxWidth: 680}}>
          约球，或者去唐岛湾游泳。
          <br />
          从服务页找到场馆公众号，查场次、办预约。
        </Lead>
      </div>
      <div style={{position: 'absolute', left: 120, top: 510}}>
        <Rule start={22} width={88} />
      </div>
      <div style={{position: 'absolute', left: 120, top: 552, display: 'flex', flexDirection: 'column', gap: 10}}>
        <FeatureRow
          icon="phone"
          title="体育场馆 · 公众号入口"
          sub="「中国石油大学体育场馆」查场次、办预约"
          n="01"
          at={20}
          active={f1}
          width={614}
        />
        <FeatureRow
          icon="web"
          title="泳馆 · 同一交接方式"
          sub="复制公众号名打开微信，不在应用内填预约"
          n="02"
          at={152}
          active={f2}
          width={614}
          tone="blue"
        />
        <FeatureRow
          icon="lock"
          title="交接边界"
          sub="不自动关注、不代填信息、不代付款"
          n="03"
          at={260}
          active={f3}
          width={614}
        />
      </div>
      <div style={{position: 'absolute', left: 120, top: 792, display: 'flex', gap: 8, flexWrap: 'wrap', width: 750}}>
        <Tag start={30} tone="green" icon={<Glyph name="wechat" size={15} color={C.brand} />}>
          仅复制名称
        </Tag>
        <Tag start={160} tone="plain">
          预约在微信内
        </Tag>
        <Tag start={270} tone="warn">
          未进行真实预约
        </Tag>
      </div>
      <div style={{position: 'absolute', left: 120, top: 844}}>
        <SourceNote>入口与交接弹窗为真实界面；未关注公众号、未提交预约、未支付，不代表预约成功。</SourceNote>
      </div>

      <div style={{position: 'absolute', left: 1290, top: 26}}>
        <ScreenDeck
          phoneWidth={420}
          glow="rgba(73,93,146,0.16)"
          screens={[
            {id: 'gym', at: 0},
            {id: 'gym-swim', at: 150},
          ]}
        />
      </div>

      <Panel width={286} at={26} out={154} style={{position: 'absolute', left: 934, top: 188}} tone="paper">
        <div style={{display: 'flex', alignItems: 'center', justifyContent: 'space-between'}}>
          <SectionLabel at={30}>体育场馆 · 交接</SectionLabel>
          <Glyph name="phone" size={16} color={C.ink3} />
        </div>
        <HandoffSteps items={['复制公众号名', '打开微信', '在公众号内预约']} />
        <div style={{fontFamily: FONT.cjk, fontSize: 11, color: C.ink3, marginTop: 9, lineHeight: 1.5}}>
          弹窗只说明下一步，不自动跳转预约页。
        </div>
      </Panel>

      <Panel width={286} at={158} style={{position: 'absolute', left: 934, top: 188}} tone="blue">
        <div style={{display: 'flex', alignItems: 'center', justifyContent: 'space-between'}}>
          <SectionLabel at={162}>泳馆 · 交接</SectionLabel>
          <Glyph name="wechat" size={16} color={C.blue} />
        </div>
        <HandoffSteps items={['复制公众号名', '打开微信', '在公众号内预约']} />
        <div
          style={{
            marginTop: 10,
            paddingTop: 8,
            borderTop: `1px solid ${C.line}`,
            fontFamily: FONT.cjk,
            fontSize: 12,
            color: C.ink3,
            lineHeight: 1.55,
          }}
        >
          两个入口都保留真实弹窗；应用不介入微信内流程。
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
        体育与泳馆 · 微信交接
      </div>
    </SceneShell>
  );
};
