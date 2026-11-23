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

const PLATFORMS = ['知到', '惟真学堂', '雨课堂', '课堂派', '石大云课堂'];

/** 01:47–02:04 · 找学习资料：服务页、五个学习平台、WebVPN 与固定网页。 */
export const Scene09: React.FC<{duration: number}> = ({duration}) => {
  const frame = useCurrentFrame();
  const f1 = il(frame, [0, 14], [0, 1]) * il(frame, [84, 104], [1, 0]);
  const f2 = il(frame, [84, 104], [0, 1]) * il(frame, [248, 268], [1, 0]);
  const f3 = il(frame, [248, 268], [0, 1]);
  return (
    <SceneShell duration={duration}>
      <div style={{position: 'absolute', left: 120, top: 138}}>
        <Eyebrow index="09" text="找学习资料" start={0} />
      </div>
      <div style={{position: 'absolute', left: 116, top: 198}}>
        <SceneTitle lines={['资料，', '少找一步。']} start={4} size={88} />
      </div>
      <div style={{position: 'absolute', left: 120, top: 392}}>
        <Lead start={14} style={{maxWidth: 680}}>
          老师把资料放在雨课堂。
          <br />
          在服务里找到学习平台，固定到首页。
        </Lead>
      </div>
      <div style={{position: 'absolute', left: 120, top: 506}}>
        <Rule start={22} width={88} />
      </div>
      <div style={{position: 'absolute', left: 120, top: 548, display: 'flex', flexDirection: 'column', gap: 10}}>
        <FeatureRow
          icon="search"
          title="服务页搜到学习平台"
          sub="搜「雨课堂」，把学习平台固定到首页"
          n="01"
          at={20}
          active={f1}
          width={614}
        />
        <FeatureRow
          icon="graduation"
          title="五个平台，浏览器打开"
          sub="知到 / 惟真学堂 / 雨课堂 / 课堂派 / 石大云课堂"
          n="02"
          at={90}
          active={f2}
          width={614}
          tone="blue"
        />
        <FeatureRow
          icon="pin"
          title="常用教务系统单独固定"
          sub="校历、数字石大、教务处与学院官网也可固定"
          n="03"
          at={252}
          active={f3}
          width={614}
        />
      </div>
      <div style={{position: 'absolute', left: 120, top: 790, display: 'flex', gap: 8, flexWrap: 'wrap', width: 750}}>
        <Tag start={30} tone="green" icon={<Glyph name="web" size={15} color={C.brand} />}>
          真实入口
        </Tag>
        <Tag start={96} tone="plain">
          固定学习平台
        </Tag>
        <Tag start={186} tone="plain">
          WebVPN
        </Tag>
        <Tag start={266} tone="warn">
          浏览器内登录
        </Tag>
      </div>
      <div style={{position: 'absolute', left: 120, top: 842}}>
        <SourceNote>服务页、学习平台与固定网页为真实界面；账号登录与网页浏览在学校页面完成。</SourceNote>
      </div>

      <div style={{position: 'absolute', left: 1290, top: 26}}>
        <ScreenDeck
          phoneWidth={420}
          glow="rgba(73,93,146,0.16)"
          screens={[
            {id: 'services', at: 0},
            {id: 'learn', at: 78},
            {id: 'home-pinned', at: 170},
            {id: 'learn', at: 254},
            {id: 'official-web', at: 344},
            {id: 'official-pinned', at: 436},
          ]}
        />
      </div>

      <Panel width={286} at={90} out={250} style={{position: 'absolute', left: 934, top: 188}} tone="blue">
        <SectionLabel at={94}>学习平台 · 5 个</SectionLabel>
        <div style={{display: 'flex', flexWrap: 'wrap', gap: 7, marginTop: 10}}>
          {PLATFORMS.map((p) => (
            <span
              key={p}
              style={{
                fontFamily: FONT.cjk,
                fontSize: 13,
                color: C.ink2,
                padding: '5px 9px',
                borderRadius: 8,
                background: 'rgba(255,255,255,0.6)',
                border: `1px solid ${C.line}`,
              }}
            >
              {p}
            </span>
          ))}
        </div>
        <div style={{fontFamily: FONT.cjk, fontSize: 12, lineHeight: 1.55, color: C.ink3, marginTop: 11}}>
          校外访问帮助与 WebVPN 在同一页，按说明登录。
        </div>
      </Panel>

      <Panel width={286} at={254} style={{position: 'absolute', left: 934, top: 188}} tone="green">
        <div style={{display: 'flex', alignItems: 'center', justifyContent: 'space-between'}}>
          <SectionLabel at={258}>固定到首页</SectionLabel>
          <Glyph name="pin" size={16} color={C.brand} />
        </div>
        <div style={{fontFamily: FONT.cjk, fontSize: 15, lineHeight: 1.7, color: C.ink2, marginTop: 8}}>
          固定只改变首页快捷入口；
          <br />
          登录、查询与下载都在学校网页里完成。
        </div>
        <div style={{fontFamily: FONT.cjk, fontSize: 12, color: C.ink3, marginTop: 9}}>
          固定结果可再调整，不影响原网页。
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
        服务与学习入口 · 真实界面
      </div>
    </SceneShell>
  );
};
