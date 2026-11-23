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

/** 00:51–01:05 · 午饭与宿舍：校园卡、微信充值入口与两校区电量。 */
export const Scene05: React.FC<{duration: number}> = ({duration}) => {
  const frame = useCurrentFrame();
  const f1 = il(frame, [0, 14], [0, 1]) * il(frame, [196, 216], [1, 0]);
  const f2 = il(frame, [196, 216], [0, 1]) * il(frame, [280, 300], [1, 0]);
  const f3 = il(frame, [280, 300], [0, 1]);
  const guz = il(frame, [360, 382], [0, 1]);
  return (
    <SceneShell duration={duration}>
      <div style={{position: 'absolute', left: 120, top: 136}}>
        <Eyebrow index="05" text="午饭与宿舍" start={0} />
      </div>
      <div style={{position: 'absolute', left: 116, top: 196}}>
        <SceneTitle lines={['卡里有钱，', '宿舍有电。']} start={4} size={86} />
      </div>
      <div style={{position: 'absolute', left: 120, top: 392}}>
        <Lead start={14} style={{maxWidth: 660}}>
          余额、流水和充值入口在一起；
          <br />
          电量则按校区，走各自真实的入口。
        </Lead>
      </div>
      <div style={{position: 'absolute', left: 120, top: 506}}>
        <Rule start={22} width={88} />
      </div>
      <div style={{position: 'absolute', left: 120, top: 546, display: 'flex', flexDirection: 'column', gap: 10}}>
        <FeatureRow
          icon="card"
          title="余额与本人流水"
          sub="流水需要可用的学校账号"
          n="01"
          at={24}
          active={f1}
        />
        <FeatureRow
          icon="wechat"
          title="微信充值入口"
          sub="金额在应用里选，付款在微信里确认"
          n="02"
          at={132}
          active={f2}
          tone="blue"
        />
        <FeatureRow
          icon="bolt"
          title="宿舍电量"
          sub="唐岛湾应用内查询；古镇口打开学校网页"
          n="03"
          at={230}
          active={f3}
        />
      </div>
      <div style={{position: 'absolute', left: 120, top: 790, display: 'flex', gap: 8, flexWrap: 'wrap', width: 720}}>
        <Tag start={46} tone="green" icon={<Glyph name="lock" size={15} color={C.brand} />}>
          账号保存在本机
        </Tag>
        <Tag start={208} tone="plain">
          未支付 · 仅入口
        </Tag>
        <Tag start={368} tone="warn">
          古镇口走学校页面
        </Tag>
      </div>
      <div style={{position: 'absolute', left: 120, top: 842}}>
        <SourceNote>余额为合成演示；流水显示未登录状态；充值未支付；电量页保留真实加载失败与入口。</SourceNote>
      </div>

      <div style={{position: 'absolute', left: 1280, top: 26}}>
        <ScreenDeck
          phoneWidth={424}
          glow="rgba(73,93,146,0.16)"
          screens={[
            {id: 'card', at: 0},
            {id: 'card-history', at: 122},
            {id: 'recharge-demo', at: 206},
            {id: 'power', at: 296},
            {id: 'power-guzhenkou', at: 368},
          ]}
        />
      </div>
      <Panel width={286} at={40} out={196} style={{position: 'absolute', left: 920, top: 208}} tone="paper">
        <SectionLabel at={44}>余额与流水</SectionLabel>
        <div style={{fontFamily: FONT.cjk, fontSize: 15, lineHeight: 1.6, color: C.ink2, marginTop: 7}}>
          总余额、过渡余额与本人交易分开显示；未设置账号时不伪造记录。
        </div>
      </Panel>
      <Panel width={286} at={214} out={356} style={{position: 'absolute', left: 920, top: 208}} tone="blue">
        <SectionLabel at={218}>微信充值</SectionLabel>
        <div style={{fontFamily: FONT.cjk, fontSize: 15, lineHeight: 1.6, color: C.ink2, marginTop: 7}}>
          充值金额与本人卡号在应用内确认，跳转微信后由用户完成付款。
        </div>
      </Panel>
      <Panel width={286} at={302} style={{position: 'absolute', left: 920, top: 208}} tone="green">
        <SectionLabel at={306}>宿舍电量</SectionLabel>
        <div style={{fontFamily: FONT.cjk, fontSize: 15, lineHeight: 1.6, color: C.ink2, marginTop: 7}}>
          {guz > 0.45
            ? '古镇口：打开学校网页查询，应用不伪造读数。'
            : '唐岛湾：在应用内选楼号与房间；失败会明确提示。'}
        </div>
      </Panel>
      <div
        style={{
          position: 'absolute',
          left: 1280,
          top: 1002,
          fontFamily: FONT.cjk,
          fontSize: 13,
          color: C.ink3,
          opacity: il(frame, [30, 54], [0, 1]),
        }}
      >
        校园卡与电费 · 真实入口
      </div>
    </SceneShell>
  );
};
