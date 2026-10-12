package com.example.videoscope.gl

/** Исходники шейдеров (GLSL ES 1.00, OpenGL ES 2.0). */
object Shaders {

    const val QUAD_VERT = """
attribute vec2 aPos;
varying vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    // Сцена: плоские цветные поля, построенные построчно из звука.
    // uAudio: текстура 512x2. Строка 0 = осциллограмма, строка 1 = спектр.
    // Экранная строка (vUv.y) выбирает сэмпл осциллограммы -> волнистые края.
    const val SCENE_FRAG = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uAudio;
uniform sampler2D uStripes;
uniform sampler2D uText;
uniform sampler2D uPhoto;
uniform float uDist;
uniform float uScroll;
uniform float uAspect;
uniform float uPhotoAspect;
uniform float uFit;
uniform float uHasPhoto;
uniform float uSwapEnv;
uniform float uSwapSeed;
uniform float uSwapCls;
uniform float uSwapCount;
uniform float uSwapScale;
uniform float uTime;
uniform float uMode;
uniform float uParam;
uniform float uBeat;
uniform float uLevel;
uniform float uBass;
uniform float uMid;
uniform float uHigh;
uniform float uSeed;
uniform float uPalette;
uniform float uMonoHue;
uniform float uMonoSat;
varying vec2 vUv;

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

vec3 hsv2rgb(vec3 c) {
    vec3 p = abs(fract(c.xxx + vec3(1.0, 0.6666667, 0.3333333)) * 6.0 - 3.0);
    return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}

// палитра: uPalette 0 = цветная (кадры Video Scope), 1 = чёрно-белая,
// 2 = монохромная (5 оттенков одного цвета). Соседние индексы контрастны по яркости.
vec3 pal(float i) {
    i = mod(i, 5.0);
    if (uPalette > 1.5) {
        float v = 0.20;
        float k = 1.0;
        if (i > 3.5)      { v = 0.65; k = 0.70; }
        else if (i > 2.5) { v = 1.00; k = 0.22; }
        else if (i > 1.5) { v = 0.45; k = 1.00; }
        else if (i > 0.5) { v = 0.85; k = 0.90; }
        return hsv2rgb(vec3(uMonoHue, uMonoSat * k, v));
    }
    if (uPalette > 0.5) {
        if (i < 0.5) return vec3(0.15);
        if (i < 1.5) return vec3(0.85);
        if (i < 2.5) return vec3(0.40);
        if (i < 3.5) return vec3(1.00);
        return vec3(0.62);
    }
    if (i < 0.5) return vec3(0.20, 0.52, 1.00);   // синий
    if (i < 1.5) return vec3(0.52, 0.42, 1.00);   // сиреневый
    if (i < 2.5) return vec3(0.88, 0.28, 0.12);   // красно-оранжевый
    if (i < 3.5) return vec3(0.25, 0.78, 0.28);   // зелёный
    return vec3(0.82, 0.90, 1.00);                // светлый бело-голубой
}

float wave(float u) {
    return (texture2D(uAudio, vec2(u, 0.25)).r - 0.5) * 2.0;
}

// Режим 0, Basic Line: вертикальные полосы, фаза каждой строки сдвинута сигналом
vec3 modeStripes(vec2 uv) {
    float w = wave(uv.y);
    float w2 = wave(fract(uv.y * 1.37 + 0.21));
    float lvl = clamp(uLevel * 1.3 + uBeat * 0.3, 0.0, 1.0);
    float halfW = 0.12 + 0.40 * lvl + w * 0.03;
    float freq = 16.0 + 30.0 * uMid + 10.0 * uBass;
    float amp = 0.45 + 1.1 * uLevel;
    float phase = uv.x * freq + (w * 1.1 + w2 * 0.4) * amp + uTime * 0.15;
    float s = step(0.5, fract(phase));
    vec3 baseLight = vec3(0.82, 0.90, 1.0);
    if (uPalette > 1.5) baseLight = pal(3.0);
    else if (uPalette > 0.5) baseLight = vec3(0.92);
    vec3 light = mix(baseLight, pal(floor(uParam * 5.0)), step(0.03, uParam));
    float inside = step(abs(uv.x - 0.5), halfW);
    return mix(light, mix(vec3(0.0), light, s), inside);
}

// Индекс палитры для Color Shifter (4 цвета, без самого светлого оттенка):
// цветная палитра: 0..3 (синий, сиреневый, красный, зелёный), без бело-голубого (4);
// ЧБ и монохром: пропускается самый светлый оттенок (индекс 3), используются 0, 1, 2, 4.
float shifterIdx(float k) {
    k = mod(k, 4.0);
    if (uPalette > 0.5 && k > 2.5) return 4.0;
    return k;
}

// Режим 1, Color Shifter: цветные зоны с волнистыми границами
vec3 modeZones(vec2 uv) {
    float w = wave(uv.y);
    float w2 = wave(fract(uv.y * 2.3 + 0.37));
    float speed = mix(0.08, 1.6, uParam);
    float amp = 0.02 + 0.10 * uMid + 0.14 * uLevel + 0.06 * uBeat;
    float wob = w * amp + w2 * amp * 0.4;
    float b1 = 0.27 + 0.10 * sin(uTime * speed + 1.0) + wob;
    float b2 = b1 + 0.22 + 0.08 * sin(uTime * speed * 0.7 + 2.0) + wob * 0.3;
    float b3 = b2 + 0.05 + 0.05 * (0.5 + 0.5 * sin(uTime * speed * 1.3 + 4.0)) + wob * 0.3;
    float z = step(b1, uv.x) + step(b2, uv.x) + step(b3, uv.x);
    float shift = floor(uTime * speed * 0.5) + floor(uSeed);
    return pal(shifterIdx(z + shift));
}

// Режим 2, Trapezoid: стопка прямоугольников/трапеций на чёрном
vec3 modeBlocks(vec2 uv) {
    float rows = 5.0;
    float band = floor(uv.y * rows);
    float fy = fract(uv.y * rows);
    float seed = uSeed + floor(uTime * 0.25);
    float h1 = hash(vec2(band, seed));
    float h2 = hash(vec2(band + 17.0, seed));
    float h3 = hash(vec2(band + 31.0, seed));
    float width = mix(0.25, 0.85, uParam) * (0.55 + 0.6 * uLevel + 0.25 * uBeat) * (0.5 + h2);
    width = clamp(width, 0.08, 0.95);
    float x0 = 0.04 + h1 * (0.92 - width);
    float slope = (h3 - 0.5) * 0.12 * (1.0 + 2.0 * uParam);
    float wb = wave(uv.y) * (0.008 + 0.05 * uLevel);
    float xl = x0 + slope * (fy - 0.5) + wb;
    float xr = x0 + width - slope * (fy - 0.5) + wb;
    float aa = 0.004;
    float m = smoothstep(xl - aa, xl + aa, uv.x) * (1.0 - smoothstep(xr - aa, xr + aa, uv.x));
    float vy = smoothstep(0.03, 0.06, fy) * (1.0 - smoothstep(0.94, 0.97, fy));
    vec3 c = pal(floor(hash(vec2(band + 5.0, seed)) * 4.0));
    return c * m * vy;
}

// Горизонтальные цветные полосы (используется в Random)
vec3 modeRuns(vec2 uv) {
    float r = floor(uv.y * 36.0);
    float rate = mix(0.6, 4.0, uParam);
    float tq = floor(uTime * rate + hash(vec2(r, 3.0)) * 4.0) + uSeed;
    float a = hash(vec2(r, tq));
    float b = hash(vec2(r + 11.0, tq));
    float lvl = 0.45 + 0.7 * uLevel + 0.3 * uBeat;
    float p1 = clamp(a * 0.8 * lvl, 0.02, 0.98);
    float p2 = clamp(p1 + (0.1 + b * 0.6) * lvl, p1 + 0.01, 1.0);
    vec3 c0 = pal(floor(hash(vec2(r + 2.0, tq)) * 4.0));
    vec3 c1 = pal(floor(hash(vec2(r + 5.0, tq)) * 4.0) + 1.0);
    vec3 c2 = pal(floor(hash(vec2(r + 8.0, tq)) * 4.0) + 2.0);
    vec3 c = mix(c0, c1, step(p1, uv.x));
    return mix(c, c2, step(p2, uv.x));
}

// Режим 3, Loader: цвета строк приходят текстурой uStripes (1 x число строк сцены).
// Логические цвета: 0, 1 = первая пара, 2, 3 = вторая пара, 4 = чёрный.
// Цветная палитра: пары синий/красно-оранжевый и сиреневый/зелёный (без светлого бело-голубого).
// ЧБ и монохром: пары берутся так, чтобы внутри пары был контраст по яркости, без самого светлого.
vec3 modeLoader(vec2 uv) {
    float idx = floor(texture2D(uStripes, vec2(0.5, uv.y)).r * 255.0 + 0.5);
    if (idx > 3.5) return vec3(0.0);
    float p = 0.0;
    if (uPalette < 0.5) {
        if (idx < 0.5) p = 0.0;
        else if (idx < 1.5) p = 2.0;
        else if (idx < 2.5) p = 1.0;
        else p = 3.0;
    } else {
        if (idx < 0.5) p = 0.0;
        else if (idx < 1.5) p = 1.0;
        else if (idx < 2.5) p = 2.0;
        else p = 4.0;
    }
    return pal(p);
}

// Режим 4, Text Glitch: текст (атлас uText) на весь экран строками. Искажение uDist (0..1):
// спокойно: текст читается; выше: колонки вытягиваются по спектру, строки сдвигаются срезами,
// появляются крупные пиксели, перестановка и зеркало; на пике текст не узнать.
// Строки движутся в разные стороны и с разной скоростью (uScroll накапливается на стороне кода).
vec3 modeText(vec2 uv) {
    float D = clamp(uDist, 0.0, 1.0);
    float e = D * D;
    float h = smoothstep(0.5, 1.0, D);
    float tick = floor(uTime * 12.0);
    vec2 p = uv;
    // волны
    p.x += sin(p.y * 30.0 + uTime * 4.0) * 0.012 * D + sin(p.y * 7.0 + uTime) * 0.01 * D * D;
    p.y += sin(p.x * 18.0 + uTime * 3.0) * 0.02 * D * D;
    // мозаика: крупные «пиксели» в случайных областях
    float cellSize = mix(1.0 / 70.0, 1.0 / 16.0, h);
    vec2 big = floor(p / (cellSize * 4.0));
    float region = step(hash(big + tick * 0.5), h * 0.7);
    p = mix(p, (floor(p / cellSize) + 0.5) * cellSize, region);
    // срезы строк
    float band = floor(p.y * 64.0);
    float sh = hash(vec2(band, tick)) - 0.5;
    float act = step(1.0 - (0.03 + 0.5 * e + 0.3 * uBeat * D), hash(vec2(band + 7.0, tick)));
    p.x += sh * act * (0.03 + 0.7 * e);
    // строки текста и растяжение колонок по настоящему спектру
    float rowsF = floor(mix(5.0, 12.0, smoothstep(0.8, 1.0, D)) + 0.5);
    float colsN = mix(16.0, 44.0, h);
    float colI = floor(p.x * colsN);
    float spec = texture2D(uAudio, vec2((colI + 0.5) / colsN, 0.75)).r;
    spec = clamp(spec * 1.3 + 0.15 * hash(vec2(colI, floor(uTime * 9.0))), 0.0, 1.0);
    float st = 1.0 + e * 7.0 * spec + smoothstep(0.2, 0.8, D) * 2.5 * hash(vec2(colI, 3.0)) + h * 6.0 * spec;
    float rc = p.y * rowsF;
    float row = floor(rc);
    float fy = fract(rc);
    fy = (fy - 0.5) / st + 0.5 + (hash(vec2(colI, row + tick)) - 0.5) * h * (st - 1.0) / st;
    // у каждой строки своё направление и целый множитель скорости (+-1, +-2, +-3): стык при обороте не виден
    float dir = (hash(vec2(row, 5.0)) < 0.5 ? -1.0 : 1.0) * (1.0 + floor(hash(vec2(row, 9.0)) * 3.0));
    float shuffle = floor(hash(vec2(row, uSeed)) * 16.0) / 16.0 * smoothstep(0.55, 0.95, D);
    float x = p.x + uScroll * dir + shuffle;
    float mir = step(1.0 - h * 0.7, hash(vec2(colI + 11.0, uSeed))) * smoothstep(0.7, 1.0, D);
    x = mix(x, 1.0 - x, mir);
    x += (fy - 0.5) * e * 0.8 * (hash(vec2(colI + 5.0, row)) - 0.5);
    // атлас загружен сверху вниз, поэтому v = 1 - fy
    float m = texture2D(uText, vec2(fract(x), 1.0 - clamp(fy, 0.0, 1.0))).r;
    m *= step(0.0, fy) * step(fy, 1.0);
    m = step(0.5, m);
    // инверсия на кик на пике
    float inv = step(0.85, D) * step(0.55, uBeat);
    m = mix(m, 1.0 - m, inv);
    // цвет: цветная палитра чередует четыре цвета по строкам, ЧБ и монохром дают светлые буквы
    vec3 fg;
    if (uPalette < 0.5) {
        float k = mod(row + floor(hash(vec2(colI, row)) * 4.0 * smoothstep(0.4, 0.9, D)) + floor(uTime * 12.0 / 16.0), 4.0);
        fg = pal(k < 0.5 ? 0.0 : (k < 1.5 ? 2.0 : (k < 2.5 ? 1.0 : 3.0)));
    } else {
        fg = pal(3.0);
    }
    return fg * m;
}

// Обмен кусков (Photo Glitch): несколько случайных прямоугольников меняются местами с другими областями.
// Размер зависит от триггера: 0 кик (крупные), 1 середина (средние), 2 высокие (мелкие).
// Ширина и высота прямоугольника независимы. Смещение не выровнено, поэтому перенесённый кусок
// может лишь частично накладываться на прежнее место или на другие куски.
vec2 swapPatches(vec2 p) {
    if (uSwapEnv < 0.08 || uSwapCount < 0.5) return p;
    float sMin = 0.25;
    float sMax = 0.60;
    if (uSwapCls > 1.5) {
        sMin = 0.03;
        sMax = 0.10;
    } else if (uSwapCls > 0.5) {
        sMin = 0.10;
        sMax = 0.25;
    }
    for (int k = 0; k < 6; k++) {
        float fk = float(k);
        if (fk >= uSwapCount) break;
        vec2 h1 = vec2(hash(vec2(fk, uSwapSeed)), hash(vec2(fk + 11.0, uSwapSeed)));
        vec2 h2 = vec2(hash(vec2(fk + 23.0, uSwapSeed)), hash(vec2(fk + 37.0, uSwapSeed)));
        vec2 h3 = vec2(hash(vec2(fk + 51.0, uSwapSeed)), hash(vec2(fk + 67.0, uSwapSeed)));
        vec2 sz = clamp((vec2(sMin) + (sMax - sMin) * h1) * uSwapScale, vec2(0.02), vec2(0.9));
        vec2 a = h2 * (vec2(1.0) - sz);
        vec2 off = (h3 - 0.5) * (sz + vec2(0.35)) * 2.0;
        vec2 b = clamp(a + off, vec2(0.0), vec2(1.0) - sz);
        vec2 d = b - a;
        vec2 inB = step(b, p) * step(p, b + sz);
        vec2 inA = step(a, p) * step(p, a + sz);
        if (inB.x * inB.y > 0.5) p -= d;
        else if (inA.x * inA.y > 0.5) p += d;
    }
    return p;
}

// Режим 5, Photo Glitch: фото (uPhoto) с искажениями: сдвиг срезов, мозаика, RGB shift.
// Сила искажения uDist (0..1) приходит из громкости с потолком.
vec3 modePhoto(vec2 uv) {
    // фото ещё не добавлены или не загружены: плоские цветные полосы-заглушка
    if (uHasPhoto < 0.5) {
        return pal(floor(uv.x * 5.0)) * step(0.5, fract(uv.y * 6.0 + uTime * 0.2));
    }
    float D = clamp(uDist, 0.0, 1.0);
    float e = D * D;
    float tick = floor(uTime * 12.0);
    vec2 p = swapPatches(uv);
    // сдвиг срезов
    float band = floor(p.y * 48.0);
    float sh = hash(vec2(band, tick)) - 0.5;
    float act = step(1.0 - (0.04 + 0.6 * D + 0.3 * uBeat), hash(vec2(band + 3.0, tick)));
    p.x += sh * act * (0.02 + 0.5 * D);
    // мозаика: крупные блоки в случайных областях
    float cell = mix(1.0 / 90.0, 1.0 / 14.0, e);
    vec2 big = floor(p / (cell * 3.0));
    float region = step(hash(big + tick * 0.5), D * 0.8);
    p = mix(p, (floor(p / cell) + 0.5) * cell, region);
    // положение в фото: заполнить (края обрезаются) или вписать (чёрные поля)
    vec2 t;
    if (uFit < 0.5) {
        if (uAspect > uPhotoAspect) t = vec2(p.x, 0.5 + (p.y - 0.5) * (uPhotoAspect / uAspect));
        else t = vec2(0.5 + (p.x - 0.5) * (uAspect / uPhotoAspect), p.y);
    } else {
        if (uAspect > uPhotoAspect) t = vec2(0.5 + (p.x - 0.5) * (uAspect / uPhotoAspect), p.y);
        else t = vec2(p.x, 0.5 + (p.y - 0.5) * (uPhotoAspect / uAspect));
    }
    float inside = step(0.0, t.x) * step(t.x, 1.0) * step(0.0, t.y) * step(t.y, 1.0);
    // RGB shift: каналы расходятся по горизонтали, зелёный чуть уходит по вертикали
    float rs = (0.003 + 0.05 * e) * (0.4 + 0.6 * hash(vec2(band, tick + 5.0)));
    vec2 tr = vec2(t.x + rs, t.y);
    vec2 tg = vec2(t.x, t.y + 0.02 * e * (hash(vec2(band, tick)) - 0.5));
    vec2 tb = vec2(t.x - rs, t.y);
    // фото загружено сверху вниз, поэтому v = 1 - y
    vec3 c;
    c.r = texture2D(uPhoto, vec2(tr.x, 1.0 - tr.y)).r;
    c.g = texture2D(uPhoto, vec2(tg.x, 1.0 - tg.y)).g;
    c.b = texture2D(uPhoto, vec2(tb.x, 1.0 - tb.y)).b;
    c *= inside;
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    if (uPalette > 0.5 && uPalette < 1.5) {
        c = vec3(l);
    } else if (uPalette > 1.5) {
        c = hsv2rgb(vec3(uMonoHue, uMonoSat * (1.0 - 0.5 * l), l));
    }
    return c;
}

void main() {
    float m = uMode;
    bool fromRandom = false;
    // Random временно отключён (RANDOM_ENABLED = false в GLRenderer): тогда uMode не бывает больше 3.
    // Индексы режимов: 0 Basic Line, 1 Color Shifter, 2 Trapezoid, 3 Loader, 4 Text Glitch, 5 Photo Glitch, 6 Random.
    if (m > 5.5) {
        // Random: смена подрежимов с регулируемой скоростью (подрежим 3 = горизонтальные полосы)
        float interval = mix(5.0, 0.6, uParam);
        m = floor(hash(vec2(floor(uTime / interval), 7.0)) * 4.0);
        fromRandom = true;
    }
    vec3 col;
    if (m < 0.5) col = modeStripes(vUv);
    else if (m < 1.5) col = modeZones(vUv);
    else if (m < 2.5) col = modeBlocks(vUv);
    else if (fromRandom) col = modeRuns(vUv);
    else if (m < 3.5) col = modeLoader(vUv);
    else if (m < 4.5) col = modeText(vUv);
    else col = modePhoto(vUv);
    gl_FragColor = vec4(col, 1.0);
}
"""

    // Аналоговая постобработка. uGlitch = 0 -> чистая картинка.
    const val POST_FRAG = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTex;
uniform float uTime;
uniform vec2 uRes;
uniform float uLevel;
uniform float uBass;
uniform float uMid;
uniform float uHigh;
uniform float uBeat;
uniform float uVhs;
uniform float uGlitch;
uniform float uNoise;
uniform float uPalette;
uniform float uMonoHue;
varying vec2 vUv;

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

vec3 hsv2rgb(vec3 c) {
    vec3 p = abs(fract(c.xxx + vec3(1.0, 0.6666667, 0.3333333)) * 6.0 - 3.0);
    return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}

void main() {
    vec2 uv = vUv;
    float gv = uVhs;     // сила VHS: полоса, scanlines, rolling bars, виньетка
    float gg = uGlitch;  // сила Glitch: сдвиги строк, цветной глитч, мерцание, RGB-кайма
    float gn = uNoise;   // сила Noise: аналоговый шум
    // spike: короткий всплеск на удар; beat: громкость + удар (непрерывная амплитуда искажений)
    float spike = uBeat * gg;
    float beat = clamp(uBeat + 0.7 * uLevel, 0.0, 1.0) * gg;
    float tick = floor(uTime * 14.0);

    // 1. сдвиги строк на бит
    float row = floor(uv.y * 60.0);
    float rr = hash(vec2(row, tick));
    float hit = step(1.0 - 0.30 * beat, rr);
    uv.x += hit * (hash(vec2(row + 3.0, tick)) - 0.5) * 0.20 * beat;

    // 2. VHS tracking: плывущая полоса + рябь
    float band = fract(uTime * 0.11);
    float d = uv.y - band;
    float inBand = exp(-d * d * 600.0);
    uv.x += inBand * sin(uv.y * 90.0 + uTime * 25.0) * 0.03 * (0.3 + uMid) * gv;
    uv.x += sin(uv.y * 240.0 + uTime * 6.0) * 0.0007 * gv;

    // 3. RGB-кайма + цветной глитч в широких строках (22 строки по высоте, в 4 раза шире прежних):
    // в цветной палитре каждый канал смещается по-своему, а иногда каналы меняются местами;
    // в ЧБ и монохроме в тех же строках вместо цвета меняется яркость
    float ca = (0.0012 + 0.010 * uLevel + 0.008 * uHigh) * gg + 0.008 * beat;
    float bw = step(0.5, uPalette);
    float rowC = floor(vUv.y * 22.5);
    float cg = step(1.0 - 0.30 * beat, hash(vec2(rowC, tick + 5.0)));
    vec3 off = (vec3(hash(vec2(rowC, tick + 1.0)),
                     hash(vec2(rowC, tick + 2.0)),
                     hash(vec2(rowC, tick + 3.0))) - 0.5) * 0.10 * beat * cg * (1.0 - bw);
    vec3 col;
    col.r = texture2D(uTex, vec2(uv.x + ca + off.x, uv.y)).r;
    col.g = texture2D(uTex, vec2(uv.x + off.y, uv.y)).g;
    col.b = texture2D(uTex, vec2(uv.x - ca + off.z, uv.y)).b;

    // перестановка каналов (цветная палитра) или смена яркости (ЧБ и монохром) в тех же широких строках
    float swp = hash(vec2(rowC * 0.5, tick + 7.0));
    float swapA = step(1.0 - 0.18 * beat, swp);
    float swapB = (1.0 - swapA) * step(swp, 0.10 * beat);
    if (bw < 0.5) {
        if (swapA > 0.5) col = col.gbr;
        else if (swapB > 0.5) col = col.brg;
    } else {
        float act = max(cg, max(swapA, swapB));
        float hb = hash(vec2(rowC, tick + 11.0));
        vec3 alt = hb < 0.34 ? 1.0 - col : (hb < 0.67 ? col * 1.9 : col * 0.25);
        col = mix(col, alt, act);
    }

    // 4. мерцание на бит
    col *= 1.0 + spike * 0.30 * (hash(vec2(tick, 1.0)) - 0.35);

    // 6. scanlines
    float line = 0.5 + 0.5 * sin(vUv.y * uRes.y * 2.0944);
    col *= 1.0 - (0.08 + 0.12 * uBass) * gv * line;

    // 7. rolling bars
    col *= 1.0 - 0.07 * gv * smoothstep(0.55, 1.0, 0.5 + 0.5 * sin(vUv.y * 6.0 - uTime * 1.4));

    // 8. аналоговый шум
    float n = hash(vUv * uRes + uTime * 60.0);
    col += (n - 0.5) * (0.02 + 0.12 * uLevel) * gn;

    // 9. лёгкая виньетка (входит в VHS)
    float vig = smoothstep(0.35, 0.95, length(vUv - 0.5));
    col *= 1.0 - 0.30 * gv * vig;

    // ЧБ: убираем цвет, включая цветные глитчи
    float lum = dot(col, vec3(0.299, 0.587, 0.114));
    if (uPalette > 0.5 && uPalette < 1.5) {
        col = vec3(lum);
    } else if (uPalette > 1.5) {
        // монохром: цвет глитчей приводится к выбранному оттенку,
        // яркость и насыщенность сохраняются
        float mx = max(col.r, max(col.g, col.b));
        float mn = min(col.r, min(col.g, col.b));
        float s0 = (mx - mn) / max(mx, 0.0001);
        col = hsv2rgb(vec3(uMonoHue, clamp(s0, 0.0, 1.0), mx));
    }

    gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
"""
}
