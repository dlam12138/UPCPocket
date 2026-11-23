import React from 'react';
import {C, FONT} from '../theme';
import {Glyph, GlyphName} from './Glyph';

type Variant = 'standard' | 'glass';

const shell = (size: number, variant: Variant, radius = 0.16): React.CSSProperties => ({
  width: size,
  height: size,
  borderRadius: size * radius,
  padding: size * 0.075,
  boxSizing: 'border-box',
  background:
    variant === 'glass'
      ? 'linear-gradient(150deg, rgba(255,255,255,0.66) 0%, rgba(255,255,255,0.36) 100%)'
      : 'linear-gradient(150deg, rgba(252,252,250,0.98) 0%, rgba(244,244,240,0.96) 100%)',
  border: `1px solid ${variant === 'glass' ? 'rgba(255,255,255,0.75)' : 'rgba(255,255,255,0.9)'}`,
  boxShadow:
    variant === 'glass'
      ? '0 20px 44px rgba(12,30,22,0.24), inset 0 1px 0 rgba(255,255,255,0.7)'
      : '0 20px 44px rgba(12,30,22,0.20), inset 0 1px 0 rgba(255,255,255,0.9)',
  backdropFilter: variant === 'glass' ? 'blur(16px)' : undefined,
  overflow: 'hidden',
  fontFamily: FONT.cjk,
  color: '#1A1B21',
});

const Head: React.FC<{size: number; title: string; right?: string}> = ({size, title, right}) => (
  <div
    style={{
      display: 'flex',
      justifyContent: 'space-between',
      alignItems: 'baseline',
      fontSize: size * 0.058,
      fontWeight: 700,
      letterSpacing: -0.2,
      marginBottom: size * 0.045,
    }}
  >
    <span>{title}</span>
    {right ? (
      <span style={{fontSize: size * 0.04, fontWeight: 500, color: '#7C7B87'}}>{right}</span>
    ) : null}
  </div>
);

export const WeekWidget: React.FC<{size: number; variant?: Variant}> = ({
  size,
  variant = 'standard',
}) => {
  const days = ['日', '一', '二', '三', '四', '五', '六'];
  const blocks = [
    {d: 0, r: 0, span: 2, name: '高数', tone: '#DDE2F9'},
    {d: 1, r: 1, span: 2, name: '物理', tone: '#D9EEE3'},
    {d: 2, r: 0, span: 2, name: '程序设计', tone: '#F5EAD6'},
    {d: 3, r: 2, span: 1, name: '英语', tone: '#F7E3DF'},
    {d: 4, r: 1, span: 1, name: '体育', tone: '#E4E0F2'},
    {d: 5, r: 2, span: 2, name: '概率论', tone: '#DDE2F9'},
    {d: 6, r: 0, span: 1, name: '实验', tone: '#D9EEE3'},
  ];
  const body = size * 0.79;
  const head = size * 0.13;
  const col = body / 7;
  const rowH = size * 0.095;
  return (
    <div style={shell(size, variant)}>
      <Head size={size} title="本周课表" right="11月22日 – 11月28日" />
      <div style={{display: 'flex', marginLeft: 0, marginBottom: size * 0.018}}>
        {days.map((d, i) => (
          <div
            key={d}
            style={{
              width: col,
              textAlign: 'center',
              fontSize: size * 0.034,
              fontWeight: 600,
              color: i === 0 ? '#495D92' : '#7C7B87',
            }}
          >
            {d}
          </div>
        ))}
      </div>
      <div style={{position: 'relative', height: body, width: col * 7}}>
        {Array.from({length: 6}).map((_, r) =>
          Array.from({length: 7}).map((__, c) => (
            <div
              key={`${r}-${c}`}
              style={{
                position: 'absolute',
                left: c * col + 1,
                top: r * rowH + 1,
                width: col - 2,
                height: rowH - 3,
                borderRadius: size * 0.012,
                background: 'rgba(73,93,146,0.045)',
              }}
            />
          )),
        )}
        {blocks.map((b) => (
          <div
            key={b.name}
            style={{
              position: 'absolute',
              left: b.d * col + 2,
              top: b.r * rowH + 2,
              width: col - 4,
              height: rowH * b.span - 5,
              borderRadius: size * 0.013,
              background: b.tone,
              padding: size * 0.012,
              boxSizing: 'border-box',
              overflow: 'hidden',
            }}
          >
            <div style={{fontSize: size * 0.026, fontWeight: 700, lineHeight: 1.2}}>{b.name}</div>
          </div>
        ))}
      </div>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          marginTop: size * 0.025,
          fontSize: size * 0.033,
          color: '#7C7B87',
        }}
      >
        <span>第 12 周 · 已同步</span>
        <span>点开完整课表 ›</span>
      </div>
    </div>
  );
};

export const TodayWidget: React.FC<{size: number; variant?: Variant}> = ({
  size,
  variant = 'standard',
}) => (
  <div style={shell(size, variant, 0.18)}>
    <Head size={size} title="今日课表" right="周日" />
    <div style={{display: 'flex', gap: size * 0.03, alignItems: 'stretch'}}>
      <div style={{width: 3, borderRadius: 3, background: C.brand, flex: 'none'}} />
      <div>
        <div style={{fontSize: size * 0.045, fontWeight: 500, color: '#474751'}}>08:00–09:35</div>
        <div style={{fontSize: size * 0.06, fontWeight: 700, marginTop: size * 0.008}}>
          高等数学 A
        </div>
        <div style={{fontSize: size * 0.042, color: '#7C7B87', marginTop: size * 0.012}}>
          南教 101
        </div>
      </div>
    </div>
    <div
      style={{
        marginTop: size * 0.055,
        paddingTop: size * 0.04,
        borderTop: '1px solid rgba(26,27,33,0.07)',
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        fontSize: size * 0.038,
        color: '#474751',
      }}
    >
      <span>下一堂 · 10:05 大学物理</span>
      <span style={{color: '#495D92'}}>›</span>
    </div>
  </div>
);

export const CardWidget: React.FC<{size: number; variant?: Variant}> = ({
  size,
  variant = 'standard',
}) => (
  <div style={shell(size, variant, 0.18)}>
    <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center'}}>
      <div style={{fontSize: size * 0.058, fontWeight: 700}}>校园卡余额</div>
      <Glyph name="card" size={size * 0.085} color="#495D92" />
    </div>
    <div style={{display: 'flex', alignItems: 'flex-end', gap: size * 0.012, marginTop: size * 0.045}}>
      <div style={{fontSize: size * 0.15, fontWeight: 800, letterSpacing: -1}}>128.40</div>
      <div style={{fontSize: size * 0.045, color: '#7C7B87', paddingBottom: size * 0.02}}>元</div>
    </div>
    <div style={{fontSize: size * 0.038, color: '#7C7B87', marginTop: size * 0.012}}>
      过渡余额 0.00 元
    </div>
    <div
      style={{
        marginTop: size * 0.055,
        paddingTop: size * 0.035,
        borderTop: '1px solid rgba(26,27,33,0.07)',
        display: 'flex',
        justifyContent: 'space-between',
        fontSize: size * 0.038,
        color: '#474751',
      }}
    >
      <span>09:41 更新</span>
      <span style={{color: '#495D92'}}>充值 ›</span>
    </div>
  </div>
);

export const PowerWidget: React.FC<{size: number; variant?: Variant}> = ({
  size,
  variant = 'standard',
}) => (
  <div style={shell(size, variant, 0.18)}>
    <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center'}}>
      <div style={{fontSize: size * 0.058, fontWeight: 700}}>唐岛湾电量</div>
      <Glyph name="bolt" size={size * 0.08} color="#A9762F" />
    </div>
    <div style={{fontSize: size * 0.04, color: '#7C7B87', marginTop: size * 0.018}}>
      12 号楼 · 508
    </div>
    <div style={{display: 'flex', alignItems: 'flex-end', gap: size * 0.012, marginTop: size * 0.035}}>
      <div style={{fontSize: size * 0.15, fontWeight: 800, letterSpacing: -1}}>62.4</div>
      <div style={{fontSize: size * 0.045, color: '#7C7B87', paddingBottom: size * 0.02}}>kWh</div>
    </div>
    <div
      style={{
        height: size * 0.025,
        borderRadius: size * 0.02,
        background: 'rgba(73,93,146,0.10)',
        marginTop: size * 0.04,
        overflow: 'hidden',
      }}
    >
      <div style={{width: '62%', height: '100%', background: C.uiGreen, borderRadius: size * 0.02}} />
    </div>
    <div style={{fontSize: size * 0.034, color: '#7C7B87', marginTop: size * 0.03}}>
      右上角刷新 · 最近成功 09:41
    </div>
  </div>
);

export const TodoWidget: React.FC<{size: number; variant?: Variant}> = ({
  size,
  variant = 'standard',
}) => (
  <div style={shell(size, variant, 0.18)}>
    <Head size={size} title="待办清单" right="3 项" />
    {[
      ['整理高等数学笔记', false],
      ['交大学物理实验报告', false],
      ['取快递', true],
    ].map(([text, done]) => (
      <div
        key={text as string}
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: size * 0.028,
          marginTop: size * 0.032,
        }}
      >
        <div
          style={{
            width: size * 0.048,
            height: size * 0.048,
            borderRadius: 999,
            border: `1.5px solid ${done ? '#1B6B4A' : 'rgba(26,27,33,0.3)'}`,
            background: done ? '#1B6B4A' : 'transparent',
            flex: 'none',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          {done ? <Glyph name="check" size={size * 0.03} color="#fff" strokeWidth={3} /> : null}
        </div>
        <div
          style={{
            fontSize: size * 0.042,
            fontWeight: 500,
            color: done ? '#7C7B87' : '#1A1B21',
            textDecoration: done ? 'line-through' : 'none',
            whiteSpace: 'nowrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
          }}
        >
          {text as string}
        </div>
      </div>
    ))}
    <div
      style={{
        marginTop: size * 0.045,
        paddingTop: size * 0.03,
        borderTop: '1px solid rgba(26,27,33,0.07)',
        fontSize: size * 0.034,
        color: '#495D92',
      }}
    >
      可在桌面完成或新增
    </div>
  </div>
);

export const DesktopBoard: React.FC<{
  width: number;
  children: React.ReactNode;
  style?: React.CSSProperties;
}> = ({width, children, style}) => {
  const height = (width * 2280) / 1080;
  return (
    <div
      style={{
        width,
        height,
        borderRadius: width * 0.1,
        overflow: 'hidden',
        position: 'relative',
        background:
          'radial-gradient(120% 90% at 18% 12%, #6E8FB9 0%, #2F4A63 46%, #172B36 100%)',
        boxShadow: '0 46px 100px -30px rgba(12,28,35,0.6)',
        ...style,
      }}
    >
      <div
        style={{
          position: 'absolute',
          inset: 0,
          background:
            'radial-gradient(60% 42% at 78% 24%, rgba(255,255,255,0.22) 0%, rgba(255,255,255,0) 70%)',
        }}
      />
      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          top: width * 0.07,
          textAlign: 'center',
          color: 'rgba(255,255,255,0.94)',
          fontFamily: FONT.cjk,
        }}
      >
        <div style={{fontSize: width * 0.1, fontWeight: 200, letterSpacing: -2}}>21:48</div>
        <div style={{fontSize: width * 0.026, marginTop: width * 0.012, opacity: 0.6}}>
          11月22日 星期日
        </div>
      </div>
      {children}
      <div
        style={{
          position: 'absolute',
          bottom: width * 0.035,
          left: width * 0.1,
          right: width * 0.1,
          height: width * 0.12,
          borderRadius: width * 0.06,
          background: 'rgba(255,255,255,0.10)',
          border: '1px solid rgba(255,255,255,0.12)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-around',
        }}
      >
        {[C.brand, '#4A78C8', '#C9A25F'].map((c, i) => (
          <div
            key={i}
            style={{
              width: width * 0.064,
              height: width * 0.064,
              borderRadius: 999,
              background: c,
              opacity: 0.9,
            }}
          />
        ))}
      </div>
    </div>
  );
};

export const widgetIcon: Record<string, GlyphName> = {
  today: 'calendar',
  week: 'calendar',
  card: 'card',
  power: 'bolt',
  todo: 'checkCircle',
};
