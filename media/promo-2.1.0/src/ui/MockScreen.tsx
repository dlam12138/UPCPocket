import React from 'react';
import {C, FONT, SCREEN} from '../theme';
import {Glyph, GlyphName} from '../components/Glyph';

/**
 * Code-drawn stand-ins for app screens that cannot be captured with real
 * account data. Every one carries a visible “示意界面 · 合成数据” mark. When a
 * matching PNG lands in public/screens, ScreenDeck replaces the whole mock.
 */

const S = SCREEN.w / 411;
const u = (v: number) => v * S;

const UI = {
  bg: '#FAF8FF',
  card: '#F3F1FA',
  card2: '#ECE9F6',
  line: 'rgba(26, 27, 33, 0.08)',
  ink: '#1A1B21',
  ink2: '#474751',
  ink3: '#7C7B87',
  primary: '#495D92',
  primarySoft: '#DDE2F9',
  green: '#1F7A52',
  greenSoft: '#D9EEE3',
  amber: '#8A5A12',
  amberSoft: '#F5EAD6',
  coral: '#A34A3E',
  coralSoft: '#F7E3DF',
} as const;

const font = (size: number, weight = 400, color: string = UI.ink): React.CSSProperties => ({
  fontFamily: FONT.cjk,
  fontSize: u(size),
  fontWeight: weight,
  color,
  lineHeight: 1.35,
  letterSpacing: 0.1,
});

const row: React.CSSProperties = {display: 'flex', alignItems: 'center'};

const Dock: React.FC<{active: number}> = ({active}) => {
  const items: {icon: GlyphName; label: string}[] = [
    {icon: 'home', label: '首页'},
    {icon: 'calendar', label: '课表'},
    {icon: 'grid', label: '服务'},
    {icon: 'timer', label: '专注'},
  ];
  return (
    <div
      style={{
        position: 'absolute',
        left: u(18),
        right: u(18),
        bottom: u(14),
        height: u(58),
        borderRadius: u(29),
        background: 'rgba(251, 249, 255, 0.92)',
        border: '1px solid rgba(255,255,255,0.9)',
        boxShadow: '0 12px 30px rgba(41, 42, 66, 0.14)',
        display: 'flex',
        alignItems: 'center',
      }}
    >
      {items.map((it, i) => (
        <div key={it.icon} style={{flex: 1, display: 'flex', justifyContent: 'center'}}>
          <div
            style={{
              width: u(42),
              height: u(42),
              borderRadius: 999,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              background: i === active ? UI.primarySoft : 'transparent',
            }}
          >
            <Glyph
              name={it.icon}
              size={u(22)}
              color={i === active ? UI.primary : UI.ink2}
              strokeWidth={1.9}
            />
          </div>
        </div>
      ))}
    </div>
  );
};

const Page: React.FC<{
  children: React.ReactNode;
  dock?: number;
  bg?: string;
  mark?: boolean;
}> = ({children, dock = 2, bg = UI.bg, mark = true}) => (
  <div style={{position: 'absolute', inset: 0, background: bg, overflow: 'hidden'}}>
    <div
      style={{
        position: 'absolute',
        left: u(24),
        right: u(24),
        top: u(22),
        bottom: u(92),
        overflow: 'hidden',
      }}
    >
      {children}
    </div>
    {mark ? (
      <div style={{position: 'absolute', left: u(26), bottom: u(80), ...font(8.5, 500, 'rgba(26,27,33,0.32)')}}>
        示意界面 · 合成演示数据
      </div>
    ) : null}
    <Dock active={dock} />
  </div>
);

const Top: React.FC<{
  title: string;
  kicker?: string;
  right?: React.ReactNode;
  sub?: string;
}> = ({title, kicker, right, sub}) => (
  <div style={{...row, justifyContent: 'space-between', paddingTop: u(10), marginBottom: u(16)}}>
    <div>
      {kicker ? (
        <div style={{...font(10, 600, UI.primary), letterSpacing: u(1.2), marginBottom: u(4)}}>{kicker}</div>
      ) : null}
      <div style={font(25, 700)}>{title}</div>
      {sub ? <div style={{...font(11.5, 400, UI.ink2), marginTop: u(3)}}>{sub}</div> : null}
    </div>
    {right}
  </div>
);

const CircleButton: React.FC<{icon: GlyphName; tone?: string}> = ({icon, tone = UI.primarySoft}) => (
  <div
    style={{
      width: u(44),
      height: u(44),
      borderRadius: 999,
      background: tone,
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
    }}
  >
    <Glyph name={icon} size={u(22)} color={UI.primary} />
  </div>
);

const Card: React.FC<{
  children: React.ReactNode;
  style?: React.CSSProperties;
  tone?: string;
}> = ({children, style, tone = UI.card}) => (
  <div
    style={{
      background: tone,
      borderRadius: u(22),
      padding: u(17),
      border: `1px solid ${UI.line}`,
      ...style,
    }}
  >
    {children}
  </div>
);

const Tag: React.FC<{text: string; tone?: string; color?: string}> = ({
  text,
  tone = UI.primarySoft,
  color = UI.primary,
}) => (
  <div
    style={{
      display: 'inline-flex',
      alignItems: 'center',
      padding: `${u(4)}px ${u(9)}px`,
      borderRadius: u(20),
      background: tone,
      ...font(9.5, 600, color),
    }}
  >
    {text}
  </div>
);

/* --------------------------------------------------------------------- home */

const HomeMock: React.FC<{pinned?: boolean}> = ({pinned}) => {
  const services: {icon: GlyphName; label: string}[] = pinned
    ? [
        {icon: 'bolt', label: '电费'},
        {icon: 'classroom', label: '空闲教室'},
        {icon: 'book', label: '图书馆'},
        {icon: 'map', label: '校园地图'},
        {icon: 'chart', label: '石光'},
        {icon: 'graduation', label: '学习平台'},
        {icon: 'users', label: '通讯录'},
        {icon: 'web', label: '官方网页'},
      ]
    : [
        {icon: 'bolt', label: '电费'},
        {icon: 'classroom', label: '空闲教室'},
        {icon: 'book', label: '图书馆'},
        {icon: 'chart', label: '石光'},
      ];
  return (
    <Page dock={0}>
      <div style={{...row, justifyContent: 'space-between', paddingTop: u(8)}}>
        <div>
          <div style={{...font(10.5, 600, UI.primary), letterSpacing: u(1.5)}}>UPC POCKET</div>
          <div style={{...font(26, 700), marginTop: u(5)}}>11月22日 星期日</div>
        </div>
        <CircleButton icon="settings" />
      </div>

      <Card style={{marginTop: u(18)}}>
        <div style={{...row, justifyContent: 'space-between'}}>
          <div style={font(15, 600)}>今日课程</div>
          <Tag text="第 12 周" tone={UI.greenSoft} color={UI.green} />
        </div>
        <div style={{...font(11, 400, UI.ink2), marginTop: u(8)}}>下一堂 · 08:00–09:35</div>
        <div style={{...font(14, 600), marginTop: u(2)}}>高等数学 · 南教 101</div>
      </Card>

      <Card style={{marginTop: u(10)}}>
        <div style={{...row, justifyContent: 'space-between'}}>
          <div style={font(15, 600)}>
            待办 <span style={{color: UI.ink3, fontSize: u(11), fontWeight: 400}}>3 项未完成</span>
          </div>
          <Glyph name="plus" size={u(20)} color={UI.ink} />
        </div>
        {['复习概率论', '交实验报告'].map((t) => (
          <div key={t} style={{...row, gap: u(8), marginTop: u(8)}}>
            <div style={{width: u(12), height: u(12), borderRadius: 999, border: `1.5px solid ${UI.ink3}`}} />
            <div style={font(11.5, 400, UI.ink2)}>{t}</div>
          </div>
        ))}
      </Card>

      <div style={{...row, justifyContent: 'space-between', marginTop: u(18)}}>
        <div style={font(14, 600)}>常用服务</div>
        <div style={font(11, 500, UI.primary)}>全部服务</div>
      </div>
      <div style={{display: 'grid', gridTemplateColumns: '1fr 1fr', gap: u(9), marginTop: u(9)}}>
        {services.map((s) => (
          <div
            key={s.label}
            style={{
              ...row,
              gap: u(9),
              background: UI.card,
              border: `1px solid ${UI.line}`,
              borderRadius: u(18),
              padding: `${u(11)}px ${u(12)}px`,
            }}
          >
            <div
              style={{
                width: u(32),
                height: u(32),
                borderRadius: 999,
                background: UI.primarySoft,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
              }}
            >
              <Glyph name={s.icon} size={u(17)} color={UI.primary} />
            </div>
            <div style={font(12, 500)}>{s.label}</div>
          </div>
        ))}
      </div>

      <div style={{...font(14, 600), marginTop: u(18)}}>校园卡</div>
      <div
        style={{
          marginTop: u(8),
          borderRadius: u(22),
          padding: u(17),
          background: 'linear-gradient(135deg, #1B6B4A 0%, #2F7D5C 58%, #3E8F6C 100%)',
          color: '#fff',
          boxShadow: '0 14px 30px rgba(27,107,74,0.24)',
        }}
      >
        <div style={{...row, justifyContent: 'space-between'}}>
          <div style={{...font(11, 500, 'rgba(255,255,255,0.78)')}}>总余额</div>
          <Glyph name="card" size={u(20)} color="rgba(255,255,255,0.9)" />
        </div>
        <div style={{...row, alignItems: 'flex-end', gap: u(5), marginTop: u(3)}}>
          <div style={{...font(30, 700, '#fff')}}>128.40</div>
          <div style={{...font(11, 500, 'rgba(255,255,255,0.72)'), paddingBottom: u(4)}}>元</div>
        </div>
        <div style={{...font(9.5, 400, 'rgba(255,255,255,0.65)'), marginTop: u(2)}}>过渡余额 0.00 元</div>
        <div style={{...row, gap: u(8), marginTop: u(12)}}>
          {['微信充值', '交易流水'].map((t) => (
            <div
              key={t}
              style={{
                flex: 1,
                textAlign: 'center',
                borderRadius: u(16),
                padding: `${u(7)}px 0`,
                background: 'rgba(255,255,255,0.16)',
                ...font(10.5, 600, '#fff'),
              }}
            >
              {t}
            </div>
          ))}
        </div>
      </div>
    </Page>
  );
};

/* ----------------------------------------------------------------- services */

const ServicesMock: React.FC = () => {
  const rows: {icon: GlyphName; title: string; sub: string; pinned?: boolean}[] = [
    {icon: 'graduation', title: '学习平台', sub: '五个入口 · 网页登录'},
    {icon: 'classroom', title: '空闲教室', sub: '按日期与节次', pinned: true},
    {icon: 'book', title: '图书馆', sub: '三馆座位', pinned: true},
    {icon: 'bolt', title: '电费', sub: '唐岛湾 / 古镇口', pinned: true},
    {icon: 'map', title: '校园地图', sub: '步行与骑行路线'},
    {icon: 'users', title: '校园通讯录', sub: '学院 / 部门 / 窗口'},
    {icon: 'web', title: '官方网页', sub: '教务系统 · 数字石大'},
  ];
  return (
    <Page dock={2}>
      <Top title="校园服务" right={null} kicker="SERVICES" />
      <div
        style={{
          ...row,
          gap: u(8),
          background: UI.card2,
          borderRadius: u(22),
          padding: `${u(9)}px ${u(13)}px`,
          marginBottom: u(12),
        }}
      >
        <Glyph name="search" size={u(16)} color={UI.ink3} />
        <div style={font(11.5, 400, UI.ink3)}>搜索服务</div>
      </div>
      <div style={{...row, gap: u(6), flexWrap: 'wrap', marginBottom: u(14)}}>
        {['全部', '学习与空间', '校园生活', '校务与成长', '官方网页'].map((t, i) => (
          <div
            key={t}
            style={{
              padding: `${u(5)}px ${u(10)}px`,
              borderRadius: u(18),
              background: i === 0 ? UI.primarySoft : 'transparent',
              border: `1px solid ${i === 0 ? 'transparent' : UI.line}`,
              ...font(9.5, 500, i === 0 ? UI.primary : UI.ink2),
            }}
          >
            {t}
          </div>
        ))}
      </div>
      <div style={{...font(11, 600, UI.ink3), marginBottom: u(7)}}>学习与空间</div>
      {rows.map((r) => (
        <div
          key={r.title}
          style={{
            ...row,
            gap: u(11),
            background: UI.card,
            border: `1px solid ${UI.line}`,
            borderRadius: u(18),
            padding: `${u(10)}px ${u(12)}px`,
            marginBottom: u(7),
          }}
        >
          <div
            style={{
              width: u(34),
              height: u(34),
              borderRadius: 999,
              background: UI.primarySoft,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <Glyph name={r.icon} size={u(18)} color={UI.primary} />
          </div>
          <div style={{flex: 1}}>
            <div style={font(12.5, 600)}>{r.title}</div>
            <div style={font(9.5, 400, UI.ink3)}>{r.sub}</div>
          </div>
          <div
            style={{
              width: u(28),
              height: u(28),
              borderRadius: 999,
              background: r.pinned ? UI.primarySoft : 'transparent',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <Glyph
              name={r.pinned ? 'pin' : 'plus'}
              size={u(15)}
              color={r.pinned ? UI.primary : UI.ink3}
              strokeWidth={r.pinned ? 2.1 : 1.6}
            />
          </div>
        </div>
      ))}
    </Page>
  );
};

/* ------------------------------------------------------------ campus setting */

const CampusMock: React.FC = () => (
  <Page dock={2}>
    <Top title="设置" kicker="SETTINGS" right={<CircleButton icon="settings" />} />
    <div style={{...font(11, 600, UI.ink3), marginBottom: u(7)}}>校园</div>
    <Card>
      <div style={{...row, justifyContent: 'space-between'}}>
        <div>
          <div style={font(13, 600)}>常用校区</div>
          <div style={{...font(9.5, 400, UI.ink3), marginTop: u(2)}}>优先打开对应校区的服务</div>
        </div>
        <Tag text="已保存" tone={UI.greenSoft} color={UI.green} />
      </div>
      <div style={{...row, gap: u(6), marginTop: u(12)}}>
        {['唐岛湾', '古镇口', '全部'].map((t, i) => (
          <div
            key={t}
            style={{
              flex: 1,
              textAlign: 'center',
              padding: `${u(7)}px 0`,
              borderRadius: u(14),
              background: i === 0 ? UI.primary : UI.card2,
              ...font(10, 600, i === 0 ? '#fff' : UI.ink2),
            }}
          >
            {t}
          </div>
        ))}
      </div>
    </Card>
    {['图书馆登录', '桌面组件', '校园官方网页'].map((t) => (
      <Card key={t} style={{marginTop: u(8)}}>
        <div style={{...row, justifyContent: 'space-between'}}>
          <div style={font(12, 500)}>{t}</div>
          <Glyph name="arrow" size={u(15)} color={UI.ink3} />
        </div>
      </Card>
    ))}
    <div style={{...font(11, 600, UI.ink3), marginTop: u(16), marginBottom: u(7)}}>应用</div>
    {['日程待办位置', '外观', '关于 UPC Pocket'].map((t) => (
      <Card key={t} style={{marginTop: u(8)}}>
        <div style={{...row, justifyContent: 'space-between'}}>
          <div style={font(12, 500)}>{t}</div>
          <Glyph name="arrow" size={u(15)} color={UI.ink3} />
        </div>
      </Card>
    ))}
  </Page>
);

/* --------------------------------------------------------------------- card */

const CardMock: React.FC<{history?: boolean; recharge?: boolean}> = ({history, recharge}) => (
  <Page dock={0}>
    <Top
      title={history ? '交易流水' : recharge ? '校园卡充值' : '校园卡'}
      kicker="CAMPUS CARD"
      right={<CircleButton icon={history ? 'receipt' : 'card'} />}
    />
    {!history && !recharge ? (
      <>
        <div
          style={{
            borderRadius: u(24),
            padding: u(20),
            background: 'linear-gradient(135deg, #173F2E 0%, #1B6B4A 55%, #359071 100%)',
            boxShadow: '0 18px 36px rgba(27,107,74,0.28)',
          }}
        >
          <div style={{...font(11, 500, 'rgba(255,255,255,0.72)')}}>总余额</div>
          <div style={{...row, alignItems: 'flex-end', gap: u(6), marginTop: u(2)}}>
            <div style={font(36, 700, '#fff')}>128.40</div>
            <div style={{...font(12, 500, 'rgba(255,255,255,0.7)'), paddingBottom: u(5)}}>元</div>
          </div>
          <div style={{...font(10, 400, 'rgba(255,255,255,0.62)'), marginTop: u(3)}}>
            过渡余额 0.00 元 · 09:41 更新
          </div>
          <div style={{...row, gap: u(8), marginTop: u(14)}}>
            {[
              {t: '微信充值', i: 'wechat' as GlyphName},
              {t: '交易流水', i: 'receipt' as GlyphName},
            ].map((b) => (
              <div
                key={b.t}
                style={{
                  ...row,
                  gap: u(6),
                  flex: 1,
                  justifyContent: 'center',
                  padding: `${u(8)}px 0`,
                  borderRadius: u(15),
                  background: 'rgba(255,255,255,0.16)',
                  ...font(10.5, 600, '#fff'),
                }}
              >
                <Glyph name={b.i} size={u(14)} color="#fff" />
                {b.t}
              </div>
            ))}
          </div>
        </div>
        <div style={{...font(12, 600), margin: `${u(16)}px 0 ${u(7)}px`}}>最近交易</div>
        {[
          ['食堂消费', '11-23 12:06', '-12.50'],
          ['微信充值', '11-22 19:31', '+100.00'],
          ['浴室消费', '11-22 21:14', '-3.20'],
        ].map(([a, b, c]) => (
          <Card key={a} style={{marginBottom: u(7)}}>
            <div style={{...row, justifyContent: 'space-between'}}>
              <div>
                <div style={font(11.5, 500)}>{a}</div>
                <div style={{...font(9, 400, UI.ink3), marginTop: u(2)}}>{b}</div>
              </div>
              <div style={{...font(12, 600, c.startsWith('+') ? UI.green : UI.ink)}}>{c}</div>
            </div>
          </Card>
        ))}
      </>
    ) : null}
    {history ? (
      <>
        <div style={{...row, gap: u(6), marginBottom: u(12)}}>
          {['全部', '收入', '支出', '2026-11'].map((t, i) => (
            <Tag key={t} text={t} tone={i === 0 ? UI.primarySoft : UI.card2} color={i === 0 ? UI.primary : UI.ink2} />
          ))}
        </div>
        {[
          ['食堂消费', '11-23 12:06', '-12.50', '一卡通'],
          ['微信充值', '11-22 19:31', '+100.00', '微信'],
          ['浴室消费', '11-22 21:14', '-3.20', '一卡通'],
          ['超市消费', '11-21 18:02', '-8.60', '一卡通'],
          ['图书馆缴费', '11-20 10:11', '-2.00', '一卡通'],
          ['补助到账', '11-19 09:00', '+30.00', '学校'],
        ].map(([a, b, c, d]) => (
          <Card key={`${a}${b}`} style={{marginBottom: u(7)}}>
            <div style={{...row, justifyContent: 'space-between'}}>
              <div>
                <div style={font(11.5, 500)}>{a}</div>
                <div style={{...font(9, 400, UI.ink3), marginTop: u(2)}}>
                  {b} · {d}
                </div>
              </div>
              <div style={{...font(12, 600, c.startsWith('+') ? UI.green : UI.ink)}}>{c}</div>
            </div>
          </Card>
        ))}
        <div style={{...font(9, 400, UI.ink3), textAlign: 'center', marginTop: u(8)}}>
          仅展示本人流水 · 演示数据
        </div>
      </>
    ) : null}
    {recharge ? (
      <>
        <Card>
          <div style={font(11, 400, UI.ink2)}>充值金额</div>
          <div style={{...row, alignItems: 'flex-end', gap: u(5), marginTop: u(4)}}>
            <div style={font(38, 700)}>50</div>
            <div style={{...font(13, 400, UI.ink2), paddingBottom: u(6)}}>元</div>
          </div>
          <div style={{display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: u(6), marginTop: u(12)}}>
            {['20', '50', '100', '200'].map((t, i) => (
              <div
                key={t}
                style={{
                  textAlign: 'center',
                  padding: `${u(6)}px 0`,
                  borderRadius: u(10),
                  background: i === 1 ? UI.primarySoft : UI.card2,
                  ...font(10, 600, i === 1 ? UI.primary : UI.ink2),
                }}
              >
                {t}
              </div>
            ))}
          </div>
        </Card>
        <div
          style={{
            ...row,
            gap: u(7),
            justifyContent: 'center',
            marginTop: u(16),
            borderRadius: u(18),
            background: '#1FA35B',
            padding: `${u(11)}px 0`,
            ...font(12, 600, '#fff'),
          }}
        >
          <Glyph name="wechat" size={u(17)} color="#fff" />
          微信充值 50 元
        </div>
        <div style={{...font(9.5, 400, UI.ink3), textAlign: 'center', marginTop: u(10), lineHeight: 1.6}}>
          充值在微信内完成；本页不代付，也不显示支付结果。
        </div>
      </>
    ) : null}
  </Page>
);

/* -------------------------------------------------------------------- power */

const PowerMock: React.FC = () => (
  <Page dock={2}>
    <Top title="唐岛湾电量" kicker="ELECTRICITY" right={<CircleButton icon="refresh" />} />
    <Card style={{background: 'linear-gradient(150deg, #F6F2E7 0%, #F1EFF8 100%)'}}>
      <div style={{...row, justifyContent: 'space-between'}}>
        <div style={font(11, 500, UI.ink2)}>12 号楼 · 508</div>
        <Tag text="演示房间" tone={UI.amberSoft} color={UI.amber} />
      </div>
      <div style={{...row, alignItems: 'flex-end', gap: u(6), marginTop: u(10)}}>
        <div style={font(48, 700)}>62.4</div>
        <div style={{...font(13, 500, UI.ink2), paddingBottom: u(8)}}>kWh</div>
      </div>
      <div style={{...font(10, 400, UI.ink3), marginTop: u(3)}}>上次成功查询 09:41</div>
      <div
        style={{
          height: u(7),
          borderRadius: u(8),
          background: UI.card2,
          marginTop: u(14),
          overflow: 'hidden',
        }}
      >
        <div style={{width: '62%', height: '100%', borderRadius: u(8), background: UI.green}} />
      </div>
    </Card>
    <Card style={{marginTop: u(10)}}>
      <div style={font(12, 600)}>查询房间</div>
      <div style={{...font(10, 400, UI.ink3), marginTop: u(4), lineHeight: 1.7}}>
        选择楼号、填写房间后主动查询。古镇口电量在「电费」中打开学校页面。
      </div>
      <div
        style={{
          marginTop: u(10),
          textAlign: 'center',
          borderRadius: u(14),
          padding: `${u(8)}px 0`,
          background: UI.primary,
          ...font(10.5, 600, '#fff'),
        }}
      >
        查询电量
      </div>
    </Card>
    <div style={{...font(9, 400, UI.ink3), marginTop: u(10), lineHeight: 1.7}}>
      数值为合成演示，不代表任何真实宿舍。
    </div>
  </Page>
);

/* ---------------------------------------------------------------- classroom */

const ClassroomMock: React.FC = () => {
  const buildings = [
    ['南教楼', '南教 101 · 102 · 105 · 201', '12 间'],
    ['文理楼', '文理 112 · 208 · 306', '8 间'],
    ['工科楼', '工科 E 区 301 · 405', '5 间'],
  ];
  return (
    <Page dock={2}>
      <Top title="空闲教室" kicker="CLASSROOMS" right={<CircleButton icon="refresh" />} />
      <div style={{...row, gap: u(6), flexWrap: 'wrap', marginBottom: u(10)}}>
        {['唐岛湾', '今天', '上午', '第 1–4 节'].map((t, i) => (
          <div
            key={t}
            style={{
              padding: `${u(5)}px ${u(10)}px`,
              borderRadius: u(16),
              background: i < 2 ? UI.primarySoft : UI.card2,
              ...font(9.5, 600, i < 2 ? UI.primary : UI.ink2),
            }}
          >
            {t}
          </div>
        ))}
      </div>
      <div style={{...row, justifyContent: 'space-between', marginBottom: u(8)}}>
        <div style={font(12, 600)}>共 25 间空闲</div>
        <div style={font(9.5, 400, UI.ink3)}>仅看空闲</div>
      </div>
      {buildings.map(([name, rooms, count]) => (
        <Card key={name} style={{marginBottom: u(8)}}>
          <div style={{...row, justifyContent: 'space-between'}}>
            <div style={font(12, 600)}>{name}</div>
            <Tag text={count} tone={UI.greenSoft} color={UI.green} />
          </div>
          <div style={{...font(9.5, 400, UI.ink3), marginTop: u(4)}}>{rooms}</div>
        </Card>
      ))}
      <div style={{...font(9, 400, UI.ink3), marginTop: u(6)}}>结果与数量为合成演示。</div>
    </Page>
  );
};

/* ------------------------------------------------------------------ library */

const LibraryMock: React.FC<{seatMap?: boolean}> = ({seatMap}) => {
  const seats = Array.from({length: 56}, (_, i) => {
    const occupied = [3, 4, 11, 12, 19, 20, 27, 33, 34, 41, 49].includes(i);
    const selected = i === 26;
    const hidden = [7, 22, 45].includes(i);
    return {i, occupied, selected, hidden};
  });
  return (
    <Page dock={0}>
      <Top
        title={seatMap ? '座位布局' : '图书馆选座'}
        kicker="LIBRARY"
        right={<CircleButton icon="seat" />}
      />
      <div style={{...row, gap: u(5), marginBottom: u(10)}}>
        {['唐岛湾', '古镇口东区', '求是'].map((t, i) => (
          <div
            key={t}
            style={{
              flex: 1,
              textAlign: 'center',
              padding: `${u(6)}px 0`,
              borderRadius: u(13),
              background: i === 0 ? UI.primary : UI.card2,
              ...font(8.8, 600, i === 0 ? '#fff' : UI.ink2),
            }}
          >
            {t}
          </div>
        ))}
      </div>
      <div style={{...row, justifyContent: 'space-between', marginBottom: u(8)}}>
        <div style={{...row, gap: u(6)}}>
          <Tag text="11-22 周日" />
          <Tag text="按座位选" tone={UI.card2} color={UI.ink2} />
        </div>
        <div style={{...font(9.5, 500, UI.primary)}}>按时段找</div>
      </div>
      <Card style={{padding: u(12)}}>
        <div style={{...row, justifyContent: 'space-between', marginBottom: u(8)}}>
          <div style={font(11, 600)}>二楼阅览区 A</div>
          <div style={{...font(9, 400, UI.ink3)}}>08:00–22:00</div>
        </div>
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(8, 1fr)',
            gap: u(5),
            background: '#EEEBF6',
            borderRadius: u(12),
            padding: u(9),
          }}
        >
          {seats.map((s) => (
            <div
              key={s.i}
              style={{
                aspectRatio: '1',
                borderRadius: u(4),
                background: s.selected
                  ? UI.green
                  : s.occupied
                    ? '#D7D3E2'
                    : s.hidden
                      ? '#F0E4DF'
                      : '#FFFFFF',
                border: `1px solid ${s.selected ? UI.green : 'rgba(26,27,33,0.08)'}`,
                opacity: s.hidden ? 0.5 : 1,
              }}
            />
          ))}
        </div>
        <div style={{...row, gap: u(11), marginTop: u(9)}}>
          {[
            ['可约', '#FFFFFF'],
            ['已占', '#D7D3E2'],
            ['已选', UI.green],
            ['本机隐藏', '#F0E4DF'],
          ].map(([t, c]) => (
            <div key={t} style={{...row, gap: u(4), ...font(8, 400, UI.ink3)}}>
              <div style={{width: u(8), height: u(8), borderRadius: u(2), background: c, border: `1px solid ${UI.line}`}} />
              {t}
            </div>
          ))}
        </div>
      </Card>
      <div style={{...row, justifyContent: 'space-between', marginTop: u(12)}}>
        <div>
          <div style={font(10, 600)}>A-026</div>
          <div style={{...font(8.5, 400, UI.ink3), marginTop: u(1)}}>08:00–12:00 · 4 小时</div>
        </div>
        <div
          style={{
            padding: `${u(7)}px ${u(16)}px`,
            borderRadius: u(15),
            background: UI.green,
            ...font(10, 600, '#fff'),
          }}
        >
          确认预约
        </div>
      </div>
      <div style={{...font(8.5, 400, UI.ink3), marginTop: u(9), lineHeight: 1.6}}>
        演示座位，不提交真实预约。个人隐藏仅保存在本机。
      </div>
    </Page>
  );
};

/* ----------------------------------------------------------------- schedule */

const ScheduleMock: React.FC = () => {
  const days = ['日', '一', '二', '三', '四', '五', '六'];
  const blocks = [
    {d: 0, p: 0, span: 2, name: '高等数学', room: '南教 101', tone: '#DDE2F9'},
    {d: 1, p: 1, span: 2, name: '大学物理', room: '文理 208', tone: '#D9EEE3'},
    {d: 2, p: 0, span: 2, name: '程序设计', room: '工科 E301', tone: '#F5EAD6'},
    {d: 3, p: 2, span: 2, name: '英语', room: '南教 205', tone: '#F7E3DF'},
    {d: 4, p: 1, span: 1, name: '体育', room: '田径场', tone: '#E4E0F2'},
    {d: 5, p: 3, span: 2, name: '概率论', room: '南教 302', tone: '#DDE2F9'},
  ];
  return (
    <Page dock={1}>
      <Top title="课表" kicker="TIMETABLE" right={<CircleButton icon="refresh" />} sub="2026 秋季学期 · 第 12 周" />
      <div style={{...row, justifyContent: 'space-between', marginBottom: u(7)}}>
        {days.map((d) => (
          <div key={d} style={{flex: 1, textAlign: 'center', ...font(9.5, 500, UI.ink3)}}>
            {d}
          </div>
        ))}
      </div>
      <Card style={{padding: u(8), background: '#F7F5FC'}}>
        <div style={{display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: u(4), position: 'relative', height: u(290)}}>
          {Array.from({length: 6}).map((_, r) =>
            Array.from({length: 7}).map((__, c) => (
              <div
                key={`${r}-${c}`}
                style={{
                  gridColumn: c + 1,
                  gridRow: r + 1,
                  borderRadius: u(6),
                  background: '#EEEBF6',
                  border: '1px solid rgba(74,68,98,0.05)',
                }}
              />
            )),
          )}
          {blocks.map((b) => (
            <div
              key={b.name}
              style={{
                position: 'absolute',
                left: `${(b.d / 7) * 100}%`,
                width: `calc(${100 / 7}% - ${u(3)}px)`,
                top: `${(b.p / 6) * 100}%`,
                height: `calc(${(b.span / 6) * 100}% - ${u(3)}px)`,
                borderRadius: u(7),
                background: b.tone,
                padding: u(4),
                overflow: 'hidden',
              }}
            >
              <div style={font(7.2, 700, UI.ink)}>{b.name}</div>
              <div style={{...font(6.2, 400, UI.ink2), marginTop: u(2)}}>{b.room}</div>
            </div>
          ))}
        </div>
      </Card>
      <div style={{...row, justifyContent: 'space-between', marginTop: u(12)}}>
        <div style={font(11, 600)}>今日课程</div>
        <div style={font(9, 400, UI.ink3)}>3 节 · 09:41 更新</div>
      </div>
      {[
        ['08:00–09:35', '高等数学', '南教 101'],
        ['10:05–11:40', '大学物理', '文理 208'],
        ['14:00–15:35', '程序设计', '工科 E301'],
      ].map(([t, n, r]) => (
        <Card key={n} style={{marginTop: u(6), padding: `${u(9)}px ${u(11)}px`}}>
          <div style={{...row, justifyContent: 'space-between'}}>
            <div style={font(10, 600)}>{n}</div>
            <div style={font(8.5, 400, UI.ink3)}>{t}</div>
          </div>
          <div style={{...font(8.5, 400, UI.ink3), marginTop: u(2)}}>{r} · 可在校园地图查看</div>
        </Card>
      ))}
      <div style={{...font(8.5, 400, UI.ink3), marginTop: u(7)}}>课程与时间为合成演示。</div>
    </Page>
  );
};

/* ------------------------------------------------------------ shiguang / e */

const ShiguangMock: React.FC = () => (
  <Page dock={2}>
    <Top title="石光" kicker="SHIGUANG" right={<CircleButton icon="chart" />} sub="第二课堂 · 只读" />
    <div style={{...row, gap: u(6), marginBottom: u(10)}}>
      {['活动', '我的'].map((t, i) => (
        <Tag key={t} text={t} tone={i === 0 ? UI.primarySoft : UI.card2} color={i === 0 ? UI.primary : UI.ink2} />
      ))}
    </div>
    <Card>
      <div style={font(11, 600)}>学分与学时</div>
      <div style={{...row, gap: u(6), marginTop: u(8), flexWrap: 'wrap'}}>
        {[
          ['思政', '0.5'],
          ['实践', '1.0'],
          ['志愿', '0.5'],
          ['创新', '0.0'],
        ].map(([k, v]) => (
          <div key={k} style={{...row, gap: u(4), background: UI.primarySoft, borderRadius: u(12), padding: `${u(4)}px ${u(8)}px`}}>
            <div style={{...font(8.5, 400, UI.primary)}}>{k}</div>
            <div style={{...font(9.5, 700, UI.primary)}}>{v}</div>
          </div>
        ))}
      </div>
    </Card>
    <div style={{...row, justifyContent: 'space-between', margin: `${u(14)}px 0 ${u(7)}px`}}>
      <div style={font(11, 600)}>活动</div>
      <div style={font(9, 400, UI.ink3)}>分类 · 隐藏已结束</div>
    </div>
    {[
      ['校园马拉松志愿者', '11-29 · 志愿服务', '可报名'],
      ['创新创业讲座', '11-26 · 讲座报告', '详情'],
      ['学院迎新晚会', '12-02 · 文体活动', '详情'],
    ].map(([t, s, tag]) => (
      <Card key={t} style={{marginBottom: u(7)}}>
        <div style={{...row, justifyContent: 'space-between'}}>
          <div>
            <div style={font(11, 600)}>{t}</div>
            <div style={{...font(8.5, 400, UI.ink3), marginTop: u(2)}}>{s}</div>
          </div>
          <Tag text={tag} tone={UI.card2} color={UI.ink2} />
        </div>
      </Card>
    ))}
    <div style={{...font(8.5, 400, UI.ink3), marginTop: u(6)}}>只读查看，不提供报名、取消或签到。</div>
  </Page>
);

const EStationMock: React.FC = () => (
  <Page dock={2}>
    <Top title="e站通" kicker="E-STATION" right={<CircleButton icon="list" />} sub="事项与本人队列 · 只读" />
    <div style={{...row, gap: u(6), marginBottom: u(10)}}>
      {['事项', '待处理', '在办'].map((t, i) => (
        <div
          key={t}
          style={{
            padding: `${u(5)}px ${u(11)}px`,
            borderRadius: u(16),
            background: i === 0 ? UI.primarySoft : UI.card2,
            ...font(9.5, 600, i === 0 ? UI.primary : UI.ink2),
          }}
        >
          {t}
        </div>
      ))}
    </div>
    <div
      style={{
        ...row,
        gap: u(8),
        background: UI.card2,
        borderRadius: u(20),
        padding: `${u(8)}px ${u(12)}px`,
        marginBottom: u(10),
      }}
    >
      <Glyph name="search" size={u(15)} color={UI.ink3} />
      <div style={font(10.5, 400, UI.ink3)}>搜索事项、部门或关键词</div>
    </div>
    {[
      ['在读证明办理', '教务处 · 线上办理', '指南'],
      ['校园卡补办', '信息化处 · 服务大厅', '指南'],
      ['教室借用申请', '教务处 · 线下提交', '指南'],
      ['奖学金材料审核', '学生工作处', '待处理'],
      ['实验室安全承诺', '实验室管理处', '在办'],
    ].map(([t, s, tag]) => (
      <Card key={t} style={{marginBottom: u(7)}}>
        <div style={{...row, justifyContent: 'space-between'}}>
          <div>
            <div style={font(11, 600)}>{t}</div>
            <div style={{...font(8.5, 400, UI.ink3), marginTop: u(2)}}>{s}</div>
          </div>
          <Tag text={tag} tone={tag === '指南' ? UI.card2 : UI.amberSoft} color={tag === '指南' ? UI.ink2 : UI.amber} />
        </div>
      </Card>
    ))}
    <div style={{...font(8.5, 400, UI.ink3), marginTop: u(6)}}>只读查看，不执行申请或审批。</div>
  </Page>
);

/* --------------------------------------------------------------------- todo */

const TodoMock: React.FC = () => (
  <Page dock={1}>
    <Top title="日程待办" kicker="TO-DO" right={<CircleButton icon="plus" />} sub="11月22日 · 3 项未完成" />
    <div style={{...row, gap: u(5), marginBottom: u(11)}}>
      {['五', '六', '日', '一', '二', '三', '四'].map((t, i) => (
        <div
          key={`${t}${i}`}
          style={{
            flex: 1,
            textAlign: 'center',
            padding: `${u(6)}px 0`,
            borderRadius: u(12),
            background: i === 2 ? UI.primary : UI.card2,
            ...font(9, 600, i === 2 ? '#fff' : UI.ink2),
          }}
        >
          {t}
        </div>
      ))}
    </div>
    {[
      ['复习概率论第三章', '今天 20:00 截止', '已设提醒', false],
      ['交大学物理实验报告', '明天 12:00 截止', '截止', false],
      ['取快递', '今天', '', true],
    ].map(([t, due, tag, done]) => (
      <Card key={t as string} style={{marginBottom: u(8)}}>
        <div style={{...row, gap: u(9), alignItems: 'flex-start'}}>
          <div
            style={{
              width: u(15),
              height: u(15),
              borderRadius: 999,
              border: `1.5px solid ${done ? UI.green : UI.ink3}`,
              background: done ? UI.green : 'transparent',
              marginTop: u(1),
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            {done ? <Glyph name="check" size={u(9)} color="#fff" strokeWidth={2.6} /> : null}
          </div>
          <div style={{flex: 1}}>
            <div
              style={{
                ...font(11.5, 600),
                textDecoration: done ? 'line-through' : 'none',
                color: done ? UI.ink3 : UI.ink,
              }}
            >
              {t as string}
            </div>
            <div style={{...row, gap: u(5), marginTop: u(3)}}>
              <div style={{...font(8.5, 400, due === '今天' ? UI.coral : UI.ink3)}}>{due as string}</div>
              {tag ? <Tag text={tag as string} tone={UI.primarySoft} color={UI.primary} /> : null}
            </div>
          </div>
        </div>
      </Card>
    ))}
    <Card style={{marginTop: u(6), background: UI.card2}}>
      <div style={{...row, gap: u(7)}}>
        <Glyph name="info" size={u(14)} color={UI.ink3} />
        <div style={{...font(9, 400, UI.ink3), lineHeight: 1.6}}>
          截止与提醒分别设置；提醒需要系统通知与精确闹钟授权。
        </div>
      </div>
    </Card>
  </Page>
);

/* -------------------------------------------------------------------- focus */

const FocusMock: React.FC = () => {
  const r = u(72);
  const circumference = 2 * Math.PI * r;
  return (
    <Page dock={3}>
      <Top title="专注" kicker="FOCUS" right={<CircleButton icon="chart" />} sub="今天已专注 1 小时 15 分" />
      <div style={{display: 'flex', justifyContent: 'center', marginTop: u(12)}}>
        <div style={{position: 'relative', width: u(200), height: u(200)}}>
          <svg width={u(200)} height={u(200)} viewBox={`0 0 ${u(200)} ${u(200)}`}>
            <circle cx={u(100)} cy={u(100)} r={r} fill="none" stroke={UI.card2} strokeWidth={u(10)} />
            <circle
              cx={u(100)}
              cy={u(100)}
              r={r}
              fill="none"
              stroke={UI.green}
              strokeWidth={u(10)}
              strokeLinecap="round"
              strokeDasharray={`${circumference * 0.68} ${circumference}`}
              transform={`rotate(-90 ${u(100)} ${u(100)})`}
            />
          </svg>
          <div
            style={{
              position: 'absolute',
              inset: 0,
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <div style={font(31, 700)}>24:36</div>
            <div style={{...font(9, 400, UI.ink3), marginTop: u(2)}}>番茄钟 · 25 分钟</div>
          </div>
        </div>
      </div>
      <div style={{...row, justifyContent: 'center', gap: u(8), marginTop: u(12)}}>
        {['暂停', '结束'].map((t, i) => (
          <div
            key={t}
            style={{
              padding: `${u(7)}px ${u(22)}px`,
              borderRadius: u(18),
              background: i === 0 ? UI.green : UI.card2,
              ...font(10, 600, i === 0 ? '#fff' : UI.ink2),
            }}
          >
            {t}
          </div>
        ))}
      </div>
      <div style={{...row, gap: u(8), marginTop: u(18)}}>
        {[
          ['本周专注', '6 小时 15 分'],
          ['完成番茄', '5 个'],
        ].map(([k, v]) => (
          <Card key={k} style={{flex: 1}}>
            <div style={{...font(8.5, 400, UI.ink3)}}>{k}</div>
            <div style={{...font(13, 700), marginTop: u(3)}}>{v}</div>
          </Card>
        ))}
      </div>
      <Card style={{marginTop: u(9)}}>
        <div style={{...row, justifyContent: 'space-between'}}>
          <div style={font(11, 600)}>今日记录</div>
          <div style={font(8.5, 400, UI.ink3)}>2 段</div>
        </div>
        <div style={{display: 'flex', gap: u(3), marginTop: u(9), alignItems: 'flex-end', height: u(38)}}>
          {[12, 20, 8, 30, 18, 26, 10, 24, 16, 34, 22, 14].map((h, i) => (
            <div key={i} style={{flex: 1, height: `${h * 2.2}%`, borderRadius: u(2), background: i % 3 === 0 ? UI.green : UI.primarySoft}} />
          ))}
        </div>
      </Card>
      <div style={{...font(8.5, 400, UI.ink3), marginTop: u(8)}}>专注记录与统计为合成演示。</div>
    </Page>
  );
};

/* ---------------------------------------------------------------------- map */

const MapGraphic: React.FC<{route?: boolean}> = ({route}) => (
  <svg viewBox="0 0 1080 1200" style={{position: 'absolute', inset: 0, width: '100%', height: '100%'}}>
    <rect width="1080" height="1200" fill="#EEF1E7" />
    {[
      {x: 90, y: 120, w: 260, h: 170, r: 18, label: '教学楼'},
      {x: 420, y: 100, w: 190, h: 140, r: 16, label: '图书馆'},
      {x: 690, y: 180, w: 260, h: 150, r: 18, label: '实验楼'},
      {x: 140, y: 430, w: 220, h: 160, r: 18, label: '学生公寓'},
      {x: 470, y: 470, w: 300, h: 140, r: 18, label: '食堂'},
      {x: 780, y: 440, w: 180, h: 190, r: 18, label: '体育馆'},
      {x: 100, y: 760, w: 250, h: 180, r: 18, label: '学院楼'},
      {x: 430, y: 780, w: 240, h: 160, r: 18, label: '田径场'},
      {x: 760, y: 780, w: 220, h: 170, r: 18, label: '行政楼'},
    ].map((b) => (
      <g key={b.label}>
        <rect x={b.x} y={b.y} width={b.w} height={b.h} rx={b.r} fill="#E1E6D6" stroke="#D3DAC6" />
        <text x={b.x + b.w / 2} y={b.y + b.h / 2 + 6} textAnchor="middle" fill="#8A9582" fontSize="20" fontFamily={FONT.cjk}>
          {b.label}
        </text>
      </g>
    ))}
    <path d="M0 330H1080M0 650H1080M0 1010H1080M360 0V1200M720 0V1200" stroke="#D8DECF" strokeWidth="26" />
    <path d="M0 330H1080M0 650H1080M0 1010H1080M360 0V1200M720 0V1200" stroke="#FBFCF8" strokeWidth="18" />
    {route ? (
      <>
        <path
          d="M250 250 L340 300 L340 620 L520 680 L760 610 L840 430 L820 340"
          fill="none"
          stroke="#1B6B4A"
          strokeWidth="13"
          strokeLinecap="round"
          strokeLinejoin="round"
          opacity="0.92"
        />
        <circle cx="250" cy="250" r="19" fill="#1B6B4A" stroke="#fff" strokeWidth="7" />
        <circle cx="820" cy="340" r="19" fill="#fff" stroke="#1B6B4A" strokeWidth="8" />
      </>
    ) : null}
  </svg>
);

const MapMock: React.FC<{route?: boolean}> = ({route}) => (
  <Page dock={2} mark={false}>
    <div style={{position: 'absolute', inset: 0}}>
      <MapGraphic route={route} />
      <div style={{position: 'absolute', left: u(18), right: u(18), top: u(8)}}>
        <div
          style={{
            ...row,
            gap: u(8),
            background: 'rgba(255,255,255,0.94)',
            borderRadius: u(22),
            padding: `${u(10)}px ${u(13)}px`,
            boxShadow: '0 10px 26px rgba(30,40,30,0.12)',
          }}
        >
          <Glyph name="search" size={u(16)} color={UI.ink3} />
          <div style={{...font(10.5, 400, UI.ink3), flex: 1}}>搜索校内地点</div>
          <Tag text="唐岛湾" />
        </div>
        <div style={{...row, gap: u(6), marginTop: u(8)}}>
          {['步行', '骑行'].map((t, i) => (
            <Tag key={t} text={t} tone={i === 0 ? UI.greenSoft : 'rgba(255,255,255,0.92)'} color={i === 0 ? UI.green : UI.ink2} />
          ))}
        </div>
      </div>
      <div
        style={{
          position: 'absolute',
          left: u(18),
          right: u(18),
          bottom: u(78),
          background: 'rgba(255,255,255,0.96)',
          borderRadius: u(22),
          padding: u(14),
          boxShadow: '0 16px 36px rgba(30,40,30,0.16)',
        }}
      >
        <div style={{...row, justifyContent: 'space-between'}}>
          <div>
            <div style={font(12, 600)}>{route ? '图书馆 → 南教楼' : '南教楼'}</div>
            <div style={{...font(9, 400, UI.ink3), marginTop: u(2)}}>
              {route ? '步行 12 分钟 · 约 900 米' : '教学楼 · 唐岛湾校区'}
            </div>
          </div>
          <Tag text={route ? '导引中' : '校内路线'} tone={UI.greenSoft} color={UI.green} />
        </div>
        <div style={{...row, gap: u(6), marginTop: u(9)}}>
          <div
            style={{
              flex: 1,
              textAlign: 'center',
              borderRadius: u(14),
              padding: `${u(7)}px 0`,
              background: UI.green,
              ...font(9.5, 600, '#fff'),
            }}
          >
            开始导航
          </div>
          <div
            style={{
              flex: 1,
              textAlign: 'center',
              borderRadius: u(14),
              padding: `${u(7)}px 0`,
              background: UI.card2,
              ...font(9.5, 600, UI.ink2),
            }}
          >
            预览路线
          </div>
        </div>
      </div>
      <div style={{position: 'absolute', right: u(22), bottom: u(190)}}>
        <div
          style={{
            width: u(38),
            height: u(38),
            borderRadius: 999,
            background: '#fff',
            boxShadow: '0 8px 20px rgba(30,40,30,0.16)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <Glyph name="navigate" size={u(19)} color={UI.green} />
        </div>
      </div>
    </div>
  </Page>
);

/* ---------------------------------------------------------------- directory */

const DirectoryMock: React.FC = () => (
  <Page dock={2}>
    <Top title="校园通讯录" kicker="DIRECTORY" right={<CircleButton icon="users" />} sub="学院 · 部门 · 办事窗口" />
    <div
      style={{
        ...row,
        gap: u(8),
        background: UI.card2,
        borderRadius: u(20),
        padding: `${u(8)}px ${u(12)}px`,
        marginBottom: u(10),
      }}
    >
      <Glyph name="search" size={u(15)} color={UI.ink3} />
      <div style={font(10.5, 400, UI.ink3)}>单位名、办事关键词或电话</div>
    </div>
    <div style={{...row, gap: u(6), marginBottom: u(11)}}>
      {['唐岛湾', '古镇口', '全部'].map((t, i) => (
        <Tag key={t} text={t} tone={i === 0 ? UI.primarySoft : UI.card2} color={i === 0 ? UI.primary : UI.ink2} />
      ))}
    </div>
    {[
      ['石油工程学院', '工科楼 B 座 · 学院办公室', '学院'],
      ['教务处', '行政楼 201 · 学籍与成绩', '部门'],
      ['就业指导中心', '大学生活动中心 · 一楼', '窗口'],
      ['图书馆服务台', '图书馆一层大厅', '窗口'],
      ['信息化处', '行政楼 305 · 校园卡', '部门'],
    ].map(([t, s, tag]) => (
      <Card key={t} style={{marginBottom: u(7)}}>
        <div style={{...row, justifyContent: 'space-between'}}>
          <div style={{flex: 1}}>
            <div style={font(11, 600)}>{t}</div>
            <div style={{...font(8.5, 400, UI.ink3), marginTop: u(2)}}>{s}</div>
          </div>
          <div style={{...row, gap: u(5)}}>
            <Tag text={tag} tone={UI.card2} color={UI.ink2} />
            <Glyph name="phone" size={u(14)} color={UI.primary} />
            <Glyph name="map" size={u(14)} color={UI.primary} />
          </div>
        </div>
      </Card>
    ))}
    <div style={{...font(8.5, 400, UI.ink3), marginTop: u(5)}}>联系电话与地点为公开信息示例。</div>
  </Page>
);

/* -------------------------------------------------------------------- learn */

const LearnMock: React.FC<{webvpn?: boolean}> = ({webvpn}) => (
  <Page dock={2}>
    <Top title={webvpn ? '校外访问帮助' : '学习平台'} kicker="LEARNING" right={<CircleButton icon="graduation" />} />
    {webvpn ? (
      <>
        <Card>
          <div style={{...row, gap: u(8)}}>
            <Glyph name="lock" size={u(18)} color={UI.primary} />
            <div style={font(12, 600)}>WebVPN 使用说明</div>
          </div>
          <div style={{...font(10, 400, UI.ink2), marginTop: u(9), lineHeight: 1.8}}>
            校外访问石大云课堂等校内网页时，可先登录学校 WebVPN，再从平台入口打开。登录与访问在外部浏览器完成。
          </div>
        </Card>
        <Card style={{marginTop: u(9)}}>
          <div style={font(11, 600)}>步骤</div>
          {['打开学校 WebVPN 并登录', '在浏览器中进入学习平台', '使用学校账号完成平台登录'].map((t, i) => (
            <div key={t} style={{...row, gap: u(8), marginTop: u(8)}}>
              <div
                style={{
                  width: u(18),
                  height: u(18),
                  borderRadius: 999,
                  background: UI.primarySoft,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  ...font(8.5, 700, UI.primary),
                }}
              >
                {i + 1}
              </div>
              <div style={font(10, 400, UI.ink2)}>{t}</div>
            </div>
          ))}
        </Card>
      </>
    ) : (
      <>
        <div style={{...row, gap: u(6), flexWrap: 'wrap', marginBottom: u(10)}}>
          {['知到', '雨课堂', '课堂派', '石大云课堂', '惟真学堂'].map((t) => (
            <Tag key={t} text={t} tone={UI.card2} color={UI.ink2} />
          ))}
        </div>
        <div style={{display: 'grid', gridTemplateColumns: '1fr 1fr', gap: u(8)}}>
          {[
            ['知到', '智慧树', 'graduation', '#DDE2F9'],
            ['雨课堂', '课程与回放', 'web', '#D9EEE3'],
            ['课堂派', '课堂互动', 'users', '#F5EAD6'],
            ['石大云课堂', '校内课程', 'book', '#F7E3DF'],
            ['惟真学堂', '通识学习', 'spark', '#E4E0F2'],
            ['WebVPN', '校外访问帮助', 'lock', '#E8EEE3'],
          ].map(([t, s, icon, bg]) => (
            <div
              key={t}
              style={{
                background: UI.card,
                border: `1px solid ${UI.line}`,
                borderRadius: u(18),
                padding: u(13),
                minHeight: u(86),
              }}
            >
              <div
                style={{
                  width: u(32),
                  height: u(32),
                  borderRadius: u(10),
                  background: bg as string,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                <Glyph name={icon as GlyphName} size={u(17)} color={UI.primary} />
              </div>
              <div style={{...font(11, 600), marginTop: u(7)}}>{t}</div>
              <div style={{...font(8.5, 400, UI.ink3), marginTop: u(1)}}>{s}</div>
            </div>
          ))}
        </div>
        <Card style={{marginTop: u(9), background: UI.card2}}>
          <div style={{...font(9, 400, UI.ink3), lineHeight: 1.7}}>
            平台登录在外部浏览器完成。学习平台入口可搜索，也可固定到首页。
          </div>
        </Card>
      </>
    )}
  </Page>
);

/* ------------------------------------------------------------- official web */

const OfficialMock: React.FC<{pinned?: boolean}> = ({pinned}) => (
  <Page dock={2}>
    <Top title="官方网页" kicker="OFFICIAL WEB" right={<CircleButton icon="web" />} sub="由系统浏览器打开" />
    <div style={{...row, gap: u(6), marginBottom: u(10)}}>
      {['全部', '学习', '生活'].map((t, i) => (
        <Tag key={t} text={t} tone={i === 0 ? UI.primarySoft : UI.card2} color={i === 0 ? UI.primary : UI.ink2} />
      ))}
    </div>
    {[
      ['教务系统', 'jwc.upc.edu.cn', true],
      ['数字石大', 'one.upc.edu.cn', true],
      ['教务处官网', 'jwc.upc.edu.cn', false],
      ['学院官网', '学院网站汇总', false],
      ['校园官方网页', '学校主页与部门网站', false],
    ].map(([t, s, pin], i) => (
      <Card key={t as string} style={{marginBottom: u(7)}}>
        <div style={{...row, justifyContent: 'space-between'}}>
          <div>
            <div style={font(11, 600)}>{t as string}</div>
            <div style={{...font(8.5, 400, UI.ink3), marginTop: u(2)}}>{s as string}</div>
          </div>
          <div
            style={{
              width: u(28),
              height: u(28),
              borderRadius: 999,
              background: (pinned && pin) || false ? UI.primarySoft : 'transparent',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <Glyph name="pin" size={u(15)} color={(pinned && pin) || false ? UI.primary : UI.ink3} />
          </div>
        </div>
      </Card>
    ))}
    <div style={{...font(8.5, 400, UI.ink3), marginTop: u(6)}}>
      固定后出现在首页「常用服务」；网页在外部浏览器打开。
    </div>
  </Page>
);

/* ---------------------------------------------------------------------- gym */

const GymMock: React.FC = () => (
  <Page dock={2}>
    <Top title="体育馆预约" kicker="SPORTS" right={<CircleButton icon="event" />} sub="中国石油大学体育场馆" />
    <Card style={{background: 'linear-gradient(140deg, #E9F3EC 0%, #F6F4EC 100%)'}}>
      <div style={{...row, gap: u(9)}}>
        <div
          style={{
            width: u(38),
            height: u(38),
            borderRadius: u(12),
            background: '#1FA35B',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <Glyph name="wechat" size={u(21)} color="#fff" />
        </div>
        <div>
          <div style={font(12, 600)}>中国石油大学体育场馆</div>
          <div style={{...font(8.5, 400, UI.ink3), marginTop: u(2)}}>微信公众号</div>
        </div>
      </div>
      <div style={{...font(10, 400, UI.ink2), marginTop: u(10), lineHeight: 1.8}}>
        体育馆：场地预定 → 场地预定
        <br />
        游泳馆：场地预定 → 游泳馆预定
      </div>
      <div
        style={{
          ...row,
          justifyContent: 'center',
          gap: u(7),
          marginTop: u(12),
          borderRadius: u(16),
          padding: `${u(9)}px 0`,
          background: '#1FA35B',
          ...font(10.5, 600, '#fff'),
        }}
      >
        <Glyph name="wechat" size={u(16)} color="#fff" />
        复制公众号名并打开微信
      </div>
    </Card>
    <Card style={{marginTop: u(9)}}>
      <div style={{...font(9.5, 400, UI.ink2), lineHeight: 1.8}}>
        应用只提供入口和公开规则，不读取余位。场次、预约提交与结果都在微信内的公众号页面完成，本页不代表预约成功。
      </div>
    </Card>
    <div style={{...row, gap: u(6), marginTop: u(10)}}>
      <Tag text="体育馆 · 唐岛湾 / 古镇口" tone={UI.card2} color={UI.ink2} />
      <Tag text="游泳馆 · 唐岛湾" tone={UI.card2} color={UI.ink2} />
    </div>
  </Page>
);

/* ----------------------------------------------------------------- settings */

const SettingsMock: React.FC<{theme?: boolean; glass?: boolean; todoPosition?: boolean}> = ({
  theme,
  glass,
  todoPosition,
}) => (
  <Page dock={0}>
    <Top
      title={theme ? '外观' : todoPosition ? '应用' : '设置'}
      kicker="SETTINGS"
      right={<CircleButton icon="settings" />}
    />
    {theme ? (
      <>
        <Card>
          <div style={font(11, 600)}>界面风格</div>
          {[
            ['Material 3', '默认', !glass],
            ['轻量玻璃', '可选 · Android 13+', glass],
          ].map(([t, s, on]) => (
            <div
              key={t as string}
              style={{
                ...row,
                justifyContent: 'space-between',
                marginTop: u(9),
                padding: u(10),
                borderRadius: u(14),
                background: on ? UI.primarySoft : UI.card2,
              }}
            >
              <div>
                <div style={font(10.5, 600, on ? UI.primary : UI.ink)}>{t as string}</div>
                <div style={{...font(8.5, 400, UI.ink3), marginTop: u(1)}}>{s as string}</div>
              </div>
              {on ? <Glyph name="checkCircle" size={u(18)} color={UI.primary} /> : null}
            </div>
          ))}
        </Card>
        <Card style={{marginTop: u(9)}}>
          <div style={font(11, 600)}>配色与深色模式</div>
          <div style={{...font(9, 400, UI.ink3), marginTop: u(4)}}>跟随系统或手动选择；桌面组件外观不受影响。</div>
        </Card>
      </>
    ) : todoPosition ? (
      <>
        <Card>
          <div style={font(11, 600)}>日程待办位置</div>
          <div style={{...font(9, 400, UI.ink3), marginTop: u(4)}}>两处共用同一份本机待办与提醒。</div>
          {[
            ['课表页顶部', '默认'],
            ['专注页计时下方', ''],
          ].map(([t, s], i) => (
            <div
              key={t}
              style={{
                ...row,
                justifyContent: 'space-between',
                marginTop: u(9),
                padding: u(10),
                borderRadius: u(14),
                background: i === 0 ? UI.primarySoft : UI.card2,
              }}
            >
              <div>
                <div style={font(10.5, 600, i === 0 ? UI.primary : UI.ink)}>{t}</div>
                {s ? <div style={{...font(8.5, 400, UI.ink3)}}>{s}</div> : null}
              </div>
              {i === 0 ? <Glyph name="checkCircle" size={u(18)} color={UI.primary} /> : null}
            </div>
          ))}
        </Card>
        <Card style={{marginTop: u(9)}}>
          <div style={{...font(9, 400, UI.ink3), lineHeight: 1.7}}>
            完整周课表仍在课表页；底部保持首页 / 课表 / 服务 / 专注四项。
          </div>
        </Card>
      </>
    ) : (
      <>
        <Card>
          <div style={{...row, justifyContent: 'space-between'}}>
            <div>
              <div style={font(11, 600)}>学校账号</div>
              <div style={{...font(9, 400, UI.ink3), marginTop: u(2)}}>密码加密保存在本机</div>
            </div>
            <Tag text="已保存" tone={UI.greenSoft} color={UI.green} />
          </div>
        </Card>
        <div style={{...font(9.5, 600, UI.ink3), margin: `${u(13)}px 0 ${u(6)}px`}}>校园</div>
        {['常用校区', '图书馆登录', '桌面组件', '校园官方网页'].map((t) => (
          <Card key={t} style={{marginBottom: u(6)}}>
            <div style={{...row, justifyContent: 'space-between'}}>
              <div style={font(10.5, 500)}>{t}</div>
              <Glyph name="arrow" size={u(14)} color={UI.ink3} />
            </div>
          </Card>
        ))}
        <div style={{...font(9.5, 600, UI.ink3), margin: `${u(13)}px 0 ${u(6)}px`}}>应用</div>
        {['日程待办位置', '外观', '检查更新'].map((t) => (
          <Card key={t} style={{marginBottom: u(6)}}>
            <div style={{...row, justifyContent: 'space-between'}}>
              <div style={font(10.5, 500)}>{t}</div>
              <Glyph name="arrow" size={u(14)} color={UI.ink3} />
            </div>
          </Card>
        ))}
        <div style={{...font(9.5, 600, UI.ink3), margin: `${u(13)}px 0 ${u(6)}px`}}>关于</div>
        {['诊断报告', 'QQ 交流群', '关于 UPC Pocket'].map((t) => (
          <Card key={t} style={{marginBottom: u(6)}}>
            <div style={{...row, justifyContent: 'space-between'}}>
              <div style={font(10.5, 500)}>{t}</div>
              <Glyph name="arrow" size={u(14)} color={UI.ink3} />
            </div>
          </Card>
        ))}
      </>
    )}
  </Page>
);

/* --------------------------------------------------------------------- main */

const CourseDetailMock: React.FC = () => (
  <Page dock={1}>
    <Top title="课程详情" kicker="COURSE" right={<CircleButton icon="calendar" />} />
    <Card>
      <div style={font(17, 700)}>高等数学</div>
      <div style={{...font(9.5, 400, UI.ink3), marginTop: u(3)}}>第 12 周 · 星期日</div>
      <div style={{...font(11, 400, UI.ink2), marginTop: u(12), lineHeight: 1.8}}>
        08:00–09:35 · 南教 101
        <br />
        教师：演示教师
        <br />
        班级：2026 级演示班
      </div>
      <div
        style={{
          ...row,
          gap: u(8),
          justifyContent: 'center',
          marginTop: u(14),
          borderRadius: u(14),
          padding: `${u(8)}px 0`,
          background: UI.greenSoft,
          ...font(10, 600, UI.green),
        }}
      >
        <Glyph name="map" size={u(15)} color={UI.green} />
        在校园地图查看
      </div>
    </Card>
    <Card style={{marginTop: u(9)}}>
      <div style={{...font(9, 400, UI.ink3), lineHeight: 1.7}}>
        课表只读，不提供编辑、导出或课程提醒。
      </div>
    </Card>
  </Page>
);

export const MockScreen: React.FC<{id: string}> = ({id}) => {
  switch (id) {
    case 'home':
      return <HomeMock />;
    case 'home-pinned':
      return <HomeMock pinned />;
    case 'services':
      return <ServicesMock />;
    case 'campus-settings':
      return <CampusMock />;
    case 'card':
      return <CardMock />;
    case 'card-history':
      return <CardMock history />;
    case 'card-recharge':
      return <CardMock recharge />;
    case 'power':
      return <PowerMock />;
    case 'classroom':
      return <ClassroomMock />;
    case 'library':
      return <LibraryMock />;
    case 'library-seatmap':
      return <LibraryMock seatMap />;
    case 'schedule':
      return <ScheduleMock />;
    case 'course-detail':
      return <CourseDetailMock />;
    case 'todo':
      return <TodoMock />;
    case 'focus':
      return <FocusMock />;
    case 'shiguang':
      return <ShiguangMock />;
    case 'estation':
      return <EStationMock />;
    case 'map':
      return <MapMock />;
    case 'map-route':
      return <MapMock route />;
    case 'directory':
      return <DirectoryMock />;
    case 'learn':
      return <LearnMock />;
    case 'learn-webvpn':
      return <LearnMock webvpn />;
    case 'official-web':
      return <OfficialMock />;
    case 'official-pinned':
      return <OfficialMock pinned />;
    case 'gym':
      return <GymMock />;
    case 'settings':
      return <SettingsMock />;
    case 'theme':
      return <SettingsMock theme />;
    case 'theme-glass':
      return <SettingsMock theme glass />;
    case 'todo-position':
      return <SettingsMock todoPosition />;
    case 'about':
      return <SettingsMock />;
    default:
      return <HomeMock />;
  }
};
