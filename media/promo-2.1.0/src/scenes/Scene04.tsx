import React from 'react';
import {useCurrentFrame} from 'remotion';
import {SceneShell} from '../components/SceneShell';
import {Eyebrow, Lead, Rise, Rule, SceneTitle, Tag} from '../components/Reveal';
import {ScreenDeck} from '../components/ScreenDeck';
import {SourceNote} from '../components/Capture';
import {FeatureRow, Panel, SectionLabel} from '../components/Blocks';
import {Glyph} from '../components/Glyph';
import {C, FONT} from '../theme';
import {il} from '../lib/anim';

const SearchCard: React.FC = () => {
  const frame = useCurrentFrame();
  const p = il(frame, [8, 30], [0, 1]) * il(frame, [88, 104], [1, 0]);
  return (
    <div
      style={{
        position: 'absolute',
        left: 934,
        top: 214,
        width: 294,
        borderRadius: 18,
        padding: '15px 17px',
        background: 'rgba(255,255,255,0.82)',
        border: `1px solid ${C.line}`,
        boxShadow: '0 22px 46px -28px rgba(16,32,26,0.35)',
        opacity: p,
        transform: `translateY(${(1 - p) * 18}px)`,
      }}
    >
      <SectionLabel at={10}>通讯录搜索</SectionLabel>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 9,
          marginTop: 9,
          padding: '9px 11px',
          borderRadius: 10,
          border: `1px solid ${C.lineStrong}`,
          background: '#fff',
        }}
      >
        <Glyph name="search" size={16} color={C.ink3} />
        <div style={{fontFamily: FONT.cjk, fontSize: 18, fontWeight: 600, color: C.ink}}>就业</div>
        <div style={{width: 1, height: 18, background: C.brand, marginLeft: -4}} />
      </div>
      <div style={{marginTop: 12, display: 'flex', flexDirection: 'column', gap: 8}}>
        {['学生就业指导中心', '就业平台咨询', '就业手续'].map((t, i) => (
          <div key={t} style={{display: 'flex', alignItems: 'center', gap: 8}}>
            <div
              style={{
                width: 5,
                height: 5,
                borderRadius: 999,
                background: i === 0 ? C.brand : C.ink3,
              }}
            />
            <div style={{fontFamily: FONT.cjk, fontSize: 14, color: i === 0 ? C.ink : C.ink3}}>
              {t}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};

const HandoffCard: React.FC<{at: number}> = ({at}) => {
  const frame = useCurrentFrame();
  const p = il(frame, [at, at + 22], [0, 1]) * il(frame, [at + 122, at + 140], [1, 0]);
  return (
    <div
      style={{
        position: 'absolute',
        left: 930,
        top: 356,
        width: 300,
        borderRadius: 18,
        padding: '16px 18px',
        background: 'rgba(13,38,28,0.9)',
        border: '1px solid rgba(255,255,255,0.12)',
        boxShadow: '0 24px 52px -30px rgba(8,24,16,0.5)',
        opacity: p,
        transform: `translateY(${(1 - p) * 18}px)`,
      }}
    >
      <div style={{display: 'flex', alignItems: 'center', gap: 9}}>
        <Glyph name="phone" size={17} color="#8FD4B0" />
        <div style={{fontFamily: FONT.cjk, fontSize: 16, fontWeight: 650, color: '#EDF2EA'}}>
          电话 → 系统拨号器
        </div>
      </div>
      <div style={{fontFamily: FONT.cjk, fontSize: 13, lineHeight: 1.6, color: 'rgba(255,255,255,0.56)', marginTop: 8}}>
        点号码只打开拨号界面，不自动外呼。
      </div>
      <div style={{height: 1, background: 'rgba(255,255,255,0.12)', margin: '12px 0'}} />
      <div style={{display: 'flex', alignItems: 'center', gap: 9}}>
        <Glyph name="map" size={17} color="#C4D1F4" />
        <div style={{fontFamily: FONT.cjk, fontSize: 16, fontWeight: 650, color: '#EDF2EA'}}>
          地址 → 应用内校园地图
        </div>
      </div>
    </div>
  );
};

/** 00:40–00:51 · 找人办事：通讯录、单位详情与电话/地址交接。 */
export const Scene04: React.FC<{duration: number}> = ({duration}) => {
  const frame = useCurrentFrame();
  const f1 = il(frame, [0, 12], [0, 1]) * il(frame, [92, 110], [1, 0]);
  const f2 = il(frame, [92, 110], [0, 1]) * il(frame, [186, 204], [1, 0]);
  const f3 = il(frame, [186, 204], [0, 1]);
  return (
    <SceneShell duration={duration}>
      <div style={{position: 'absolute', left: 120, top: 142}}>
        <Eyebrow index="04" text="找人办事" start={0} />
      </div>
      <div style={{position: 'absolute', left: 116, top: 202}}>
        <SceneTitle lines={['先找到人，', '再办事。']} start={4} size={88} />
      </div>
      <div style={{position: 'absolute', left: 120, top: 404}}>
        <Lead start={14} style={{maxWidth: 670}}>
          校园通讯录，按单位、事项或楼栋查。
          <br />
          两个校区，一个入口。
        </Lead>
      </div>
      <div style={{position: 'absolute', left: 120, top: 520}}>
        <Rule start={22} width={88} />
      </div>
      <div style={{position: 'absolute', left: 120, top: 560, display: 'flex', flexDirection: 'column', gap: 10}}>
        <FeatureRow
          icon="search"
          title="搜单位、事项、楼栋"
          sub="也能按电话号码找"
          n="01"
          at={24}
          active={f1}
        />
        <FeatureRow
          icon="users"
          title="学院、部门与办事窗口"
          sub="单位详情列出窗口、职责与来源"
          n="02"
          at={36}
          active={f2}
        />
        <FeatureRow
          icon="phone"
          title="电话与关联地址"
          sub="拨号器由系统打开，关联地址在校园地图查看"
          n="03"
          at={188}
          active={f3}
          tone="blue"
        />
      </div>
      <div style={{position: 'absolute', left: 120, top: 803}}>
        <SourceNote>公开联络信息；电话由系统拨号器打开，地图在应用内打开。</SourceNote>
      </div>
      <div style={{position: 'absolute', left: 650, top: 806, display: 'flex', gap: 8}}>
        <Tag start={40} tone="plain">
          唐岛湾
        </Tag>
        <Tag start={48} tone="plain">
          古镇口
        </Tag>
      </div>

      <div style={{position: 'absolute', left: 1290, top: 38}}>
        <ScreenDeck
          phoneWidth={416}
          glow="rgba(73,93,146,0.16)"
          screens={[
            {id: 'directory', at: 0},
            {id: 'directory-detail', at: 96},
            {id: 'directory-window', at: 190},
          ]}
        />
      </div>
      <SearchCard />
      <HandoffCard at={196} />
      <Rise start={196} style={{position: 'absolute', left: 1290, top: 1006}}>
        <div style={{fontFamily: FONT.cjk, fontSize: 13, color: C.ink3}}>
          真实通讯录 · 唐岛湾校区
        </div>
      </Rise>
      <Panel width={254} at={206} style={{position: 'absolute', left: 944, top: 730}} tone="green">
        <SectionLabel at={210}>来源</SectionLabel>
        <div style={{fontFamily: FONT.cjk, fontSize: 14, lineHeight: 1.6, color: C.ink2, marginTop: 6}}>
          详情页保留官网来源与核对日期。
        </div>
      </Panel>
    </SceneShell>
  );
};
