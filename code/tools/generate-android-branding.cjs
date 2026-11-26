// Deterministic resource conversion: original logos in method/design are read-only inputs.
const fs = require("fs");
const path = require("path");
const { createRequire } = require("module");
const sharp = createRequire(
  path.resolve(__dirname, "../mobile-app/package.json")
)("sharp");
const root = path.resolve(__dirname, "../..");
const res = path.join(root, "code/mobile-app/android/app/src/main/res");
const store = path.join(root, "code/mobile-app/store");
const source = path.join(
  root,
  "method/design/2026-11-10 – Smart Stock Medical Cabinet - Logo1 Without BG.png"
);
const preview = process.argv.includes("--preview");
const output = (p, data) => {
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, data);
};
const mask = (size, round) =>
  Buffer.from(
    `<svg width="${size}" height="${size}"><rect width="${size}" height="${size}" rx="${
      round ? size / 2 : size * 0.22
    }" fill="white"/></svg>`
  );
const background = (size) =>
  Buffer.from(
    `<svg width="${size}" height="${size}"><defs><linearGradient id="bg" x2="1" y2="1"><stop stop-color="#eaf4ff"/><stop offset="1" stop-color="#cef3ec"/></linearGradient></defs><rect width="100%" height="100%" fill="url(#bg)"/></svg>`
  );
async function main() {
  const before = fs.readFileSync(source);
  const logo = await sharp(before).trim({ threshold: 1 }).png().toBuffer();
  async function foreground(canvas, fraction) {
    const image = await sharp(logo)
      .resize(Math.round(canvas * fraction), Math.round(canvas * fraction), {
        fit: "inside",
      })
      .png()
      .toBuffer();
    return sharp({
      create: {
        width: canvas,
        height: canvas,
        channels: 4,
        background: "#00000000",
      },
    })
      .composite([{ input: image, gravity: "centre" }])
      .png()
      .toBuffer();
  }
  async function icon(size, round = false) {
    const square = await sharp(background(size))
      .composite([{ input: await foreground(size, 0.66) }])
      .ensureAlpha()
      .toColourspace("srgb")
      .png()
      .toBuffer();
    return round
      ? sharp(square)
          .composite([{ input: mask(size, true), blend: "dest-in" }])
          .png()
          .toBuffer()
      : square;
  }
  for (const [density, scale] of [
    ["mdpi", 1],
    ["hdpi", 1.5],
    ["xhdpi", 2],
    ["xxhdpi", 3],
    ["xxxhdpi", 4],
  ]) {
    output(
      path.join(res, `mipmap-${density}/ic_launcher.png`),
      await icon(48 * scale)
    );
    output(
      path.join(res, `mipmap-${density}/ic_launcher_round.png`),
      await icon(48 * scale, true)
    );
    // Bounding square 46dp: diagonal <66dp safe circle, on a 108dp adaptive canvas.
    output(
      path.join(res, `mipmap-${density}/ic_launcher_foreground.png`),
      await foreground(108 * scale, 46 / 108)
    );
    // Without icon background: 288dp canvas, artwork diagonal <192dp.
    output(
      path.join(res, `drawable-${density}/smartstock_splash_icon.png`),
      await foreground(288 * scale, 132 / 288)
    );
  }
  const play = await sharp(await icon(512))
    .withIccProfile("srgb")
    .ensureAlpha()
    .png({ palette: false, compressionLevel: 9 })
    .toBuffer();
  if (play.length > 1024 * 1024) throw Error("Play icon exceeds 1024KB");
  output(path.join(store, "google-play-icon.png"), play);
  // Validate every foreground pixel against both Android mask shapes at the visible 72dp viewport.
  const adaptive = await foreground(432, 46 / 108);
  const { data, info } = await sharp(adaptive)
    .raw()
    .toBuffer({ resolveWithObject: true });
  let pixels = 0;
  for (let y = 0; y < info.height; y++)
    for (let x = 0; x < info.width; x++) {
      if (!data[(y * info.width + x) * 4 + 3]) continue;
      pixels++;
      if (Math.hypot(x + 0.5 - 216, y + 0.5 - 216) > 132)
        throw Error("Artwork outside 66dp safe circle");
    }
  if (!pixels || !before.equals(fs.readFileSync(source)))
    throw Error("Source changed or artwork empty");
  if (preview) {
    const folder = path.join(root, "carnet-de-bord/branding");
    fs.mkdirSync(folder, { recursive: true });
    const fullView = await sharp(background(432))
      .composite([{ input: adaptive }])
      .png()
      .toBuffer();
    const adaptiveView = await sharp(fullView)
      .extract({ left: 72, top: 72, width: 288, height: 288 })
      .png()
      .toBuffer();
    const images = [];
    for (const round of [true, false]) {
      const img = await sharp(adaptiveView)
        .composite([{ input: mask(288, round), blend: "dest-in" }])
        .png()
        .toBuffer();
      output(
        path.join(
          folder,
          round ? "adaptive-circle.png" : "adaptive-rounded-square.png"
        ),
        img
      );
      images.push(img);
    }
    output(
      path.join(folder, "mask-contact-sheet.png"),
      await sharp({
        create: { width: 768, height: 440, channels: 4, background: "#ffffff" },
      })
        .composite([
          { input: images[0], left: 48, top: 48 },
          { input: images[1], left: 432, top: 48 },
          { input: await icon(48, true), left: 160, top: 360 },
          { input: await icon(48), left: 544, top: 360 },
        ])
        .png()
        .toBuffer()
    );
    output(
      path.join(folder, "splash-preview.png"),
      await sharp({
        create: { width: 390, height: 844, channels: 4, background: "#073646" },
      })
        .composite([
          { input: await foreground(288, 132 / 288), gravity: "centre" },
        ])
        .png()
        .toBuffer()
    );
  }
  console.log(
    `Generated Android densities, safe adaptive layers, splash and Play icon (${play.length} bytes); source unchanged.`
  );
}
main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
