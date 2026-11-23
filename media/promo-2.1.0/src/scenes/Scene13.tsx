import React from 'react';
import {useCurrentFrame} from 'remotion';
import {SceneShell} from '../components/SceneShell';
import {Eyebrow, Lead, Rule, SceneTitle, Tag} from '../components/Reveal';
import {ScreenDeck} from '../components/ScreenDeck';
import {PlainShot, SourceNote} from '../components/Capture';
import {BrandIcon} from '../components/BrandIcon';
import {VersionChip} from '../components/Brand';
import {FeatureRow, Panel, SectionLabel} from '../components/Blocks';
import {Glyph} from '../components/Glyph';
import {C, FONT} from '../theme';
import {il} from '../lib/anim';

const Choice: React.FC<{label: string; active?: boolean}> = ({label, active}) => (
  <span
    style={{
      padding: '6px 11px',
      borderRadius: 9,
      fontFamily: FONT.cjk,
      fontSize: 13,
      fontWeight: active ? 700 : 500,
      color: active ? C.blue : C.ink2,
      background: active ? 'rgba(255,255,255,0.86)' : 'rgba(255,255,255,0.32)',
      border: `1px solid ${active ? 'rgba(73,93,146,0.38)' : C.line}`,
    }}
  >
    {label}
  </span>
);

/** 02:50–03:06 · 收尾：常用校区、外观、账号、关于与最终标识。 */
export const Scene13: React.FC<{duration: number}> = ({duration}) => {
  const frame = useCurrentFrame();
  const f1 = il(frame, [0, 14], [0, 1]) * il(frame, [108, 128], [1, 0]);
  const f2 = il(frame, [108, 128], [0, 1]) * il(frame, [196, 216], [1, 0]);
  const f3 = il(frame, [196, 216], [0, 1]);
  const end = il(frame, [328, 356], [0, 1]);
  return (
    <SceneShell duration={duration}>
      <div style={{position: 'absolute', left: 120, top: 138, opacity: 1 - end}}>
        <Eyebrow index="13" text="收尾" start={0} />
      </div>
      <div style={{position: 'absolute', left: 116, top: 198, opacity: 1 - end}}>
        <SceneTitle lines={['收好，', '按你的习惯。']} start={4} size={86} />
      </div>
      <div style={{position: 'absolute', left: 120, top: 392, opacity: 1 - end}}>
        <Lead start={14} style={{maxWidth: 680}}>
          常用校区、外观、账号与关于页。
          <br />
          需要的设置，都在这里。
        </Lead>
      </div>
      <div style={{position: 'absolute', left: 120, top: 506, opacity: 1 - end}}>
        <Rule start={22} width={88} />
      </div>
      <div
        style={{
          position: 'absolute',
          left: 120,
          top: 548,
          display: 'flex',
          flexDirection: 'column',
          gap: 10,
          opacity: 1 - end,
        }}
      >
        <FeatureRow
          icon="settings"
          title="常用校区与轻量玻璃"
          sub="默认校区跟手切换；深浅色可跟随系统"
          n="01"
          at={20}
          active={f1}
          width={614}
        />
        <FeatureRow
          icon="lock"
          title="学校账号只留本机"
          sub="加密保存；保存状态不等于学校认证通过"
          n="02"
          at={112}
          active={f2}
          width={614}
          tone="blue"
        />
        <FeatureRow
          icon="info"
          title="更新、诊断与交流群"
          sub="都在关于页；更新失败会如实提示"
          n="03"
          at={200}
          active={f3}
          width={614}
        />
      </div>
      <div
        style={{
          position: 'absolute',
          left: 120,
          top: 796,
          display: 'flex',
          gap: 8,
          flexWrap: 'wrap',
          width: 760,
          opacity: 1 - end,
        }}
      >
        <Tag start={30} tone="green" icon={<Glyph name="lock" size={15} color={C.brand} />}>
          账号不上传
        </Tag>
        <Tag start={122} tone="plain">
          轻量玻璃
        </Tag>
        <Tag start={208} tone="plain">
          诊断可导出
        </Tag>
        <Tag start={220} tone="warn">
          不伪造更新成功
        </Tag>
      </div>
      <div style={{position: 'absolute', left: 120, top: 846, opacity: 1 - end}}>
        <SourceNote>设置为真实界面；账号区显示未设置状态，不伪造已登录或认证通过。</SourceNote>
      </div>

      <div style={{position: 'absolute', left: 1290, top: 26, opacity: 1 - end}}>
        <ScreenDeck
          phoneWidth={400}
          glow="rgba(73,93,146,0.14)"
          screens={[
            {id: 'settings', at: 0},
            {id: 'campus-settings', at: 32},
            {id: 'theme', at: 88},
            {id: 'theme-glass', at: 138},
            {id: 'settings', at: 200},
            {id: 'about', at: 250},
          ]}
        />
      </div>

      <div style={{opacity: 1 - end}}>
        <Panel width={286} at={34} out={116} style={{position: 'absolute', left: 934, top: 188}} tone="green">
          <SectionLabel at={38}>常用校区</SectionLabel>
          <div style={{display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 9}}>
            <Choice label="全部校区" />
            <Choice label="唐岛湾" active />
            <Choice label="古镇口" />
          </div>
          <div style={{fontFamily: FONT.cjk, fontSize: 12, lineHeight: 1.55, color: C.ink3, marginTop: 9}}>
            功能页默认打开所选校区，页内仍可临时切换。
          </div>
        </Panel>

        <Panel width={286} at={120} out={252} style={{position: 'absolute', left: 934, top: 188}} tone="paper">
          <SectionLabel at={124}>外观</SectionLabel>
          <div style={{display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 9}}>
            <Choice label="Material 3" />
            <Choice label="轻量玻璃" active />
          </div>
          <div style={{display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 7}}>
            <Choice label="跟随系统" active />
            <Choice label="浅色" />
            <Choice label="深色" />
          </div>
          <div style={{fontFamily: FONT.cjk, fontSize: 12, color: C.ink3, marginTop: 9}}>
            配色可跟随壁纸，也可手动选择。
          </div>
        </Panel>

        <Panel width={286} at={256} out={332} style={{position: 'absolute', left: 934, top: 188}} tone="blue">
          <div style={{display: 'flex', alignItems: 'center', justifyContent: 'space-between'}}>
            <SectionLabel at={260}>账号与隐私</SectionLabel>
            <Glyph name="lock" size={16} color={C.blue} />
          </div>
          <div style={{fontFamily: FONT.cjk, fontSize: 14, lineHeight: 1.65, color: C.ink2, marginTop: 8}}>
            账号加密保存在本机。
            <br />
            保存只表示“已保存”，仍需实际验证。
          </div>
        </Panel>

        <div style={{position: 'absolute', left: 934, top: 474}}>
          <SectionLabel at={212}>诊断 · 导出卡片</SectionLabel>
          <div style={{marginTop: 8}}>
            <PlainShot id="diagnostics" width={318} radius={18} shadow />
          </div>
          <div style={{fontFamily: FONT.cjk, fontSize: 11, color: C.ink3, marginTop: 7, maxWidth: 318, lineHeight: 1.5}}>
            只记录固定状态码；由用户主动导出分享。
          </div>
        </div>

        <div
          style={{
            position: 'absolute',
            left: 934,
            top: 664,
            width: 286,
            padding: '12px 15px',
            borderRadius: 14,
            background: 'rgba(255,255,255,0.5)',
            border: `1px solid ${C.line}`,
            opacity: il(frame, [244, 266], [0, 1]),
          }}
        >
          <div style={{display: 'flex', alignItems: 'center', gap: 8}}>
            <Glyph name="users" size={16} color={C.ink3} />
            <div style={{fontFamily: FONT.cjk, fontSize: 13, fontWeight: 650, color: C.ink}}>QQ 交流群</div>
          </div>
          <div style={{fontFamily: FONT.cjk, fontSize: 11, color: C.ink3, marginTop: 4}}>
            群号与更新、诊断一起放在关于页。
          </div>
        </div>
      </div>

      <div
        style={{
          position: 'absolute',
          left: 0,
          top: 0,
          width: 1920,
          height: 1080,
          background:
            'radial-gradient(90% 80% at 50% 38%, #FAF7EE 0%, #F1F3EC 54%, #E7ECDF 100%)',
          opacity: end,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        <div style={{transform: `translateY(${(1 - end) * 18}px)`, display: 'flex', flexDirection: 'column', alignItems: 'center'}}>
          <BrandIcon size={108} radius={26} />
          <div
            style={{
              fontFamily: FONT.mono,
              fontSize: 13,
              letterSpacing: 4,
              color: C.ink3,
              marginTop: 26,
            }}
          >
            UPC POCKET
          </div>
          <div
            style={{
              fontFamily: FONT.cjk,
              fontSize: 92,
              fontWeight: 800,
              letterSpacing: -1,
              color: C.ink,
              marginTop: 10,
              lineHeight: 1.1,
            }}
          >
            从早八到晚自习
          </div>
          <div style={{marginTop: 24}}>
            <VersionChip style={{fontSize: 19, padding: '9px 18px'}} />
          </div>
          <div
            style={{
              fontFamily: FONT.cjk,
              fontSize: 19,
              color: C.ink3,
              marginTop: 20,
              letterSpacing: 0.2,
            }}
          >
            中国石油大学（华东）非官方 Android 校园工具 · Android 12+
          </div>
          <div style={{fontFamily: FONT.cjk, fontSize: 13, color: C.ink3, marginTop: 10, opacity: 0.8}}>
            功能预览 · 合成数据已在画面中标注
          </div>
        </div>
      </div>
    </SceneShell>
  );
};
