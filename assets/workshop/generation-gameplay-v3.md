# 第三版封面 / Cover version 3

通过 imagegen 编辑第二版生成图，在左上角增加腐烂苹果 → 新鲜苹果的图示，以低分辨率物品图标质感说明返鲜功能。

图片为生成作品，不是 Project Zomboid 实机截图。This is AI-generated artwork, not an actual Project Zomboid gameplay screenshot.

- 编辑输入：[cover-gameplay-v2.png](cover-gameplay-v2.png)。
- 完整输出：[cover-gameplay-v3.png](cover-gameplay-v3.png)。
- 512×512 工坊预览：[preview-gameplay-v3.png](preview-gameplay-v3.png)，445046 字节；当前 [preview.png](preview.png) 使用同一文件内容。
- 预览 SHA-256：`CF91E1B5798FBAECF2898FF2F61AB84F66BE881694BABD0A8935C2121478B6BD`。
- 下方提示词记录编辑意图，不构成原图其他区域逐像素不变的保证。

## 完整编辑提示词

```text
Use case: precise-object-edit
Asset type: Steam Workshop mod cover, add a small gameplay-function illustration.
Input image: the supplied image is the EDIT TARGET and approved base artwork.
User request: Keep everything else unchanged. Add a separately designed rotten fruit with an arrow pointing to the same fruit fresh, matching Project Zomboid's vanilla inventory-item art style.

Make ONLY the following local addition:
In the upper-left area over the wooden upper cabinets, arrange a single compact horizontal row: one rotten apple icon on the left, one simple right-pointing arrow in the middle, and the same apple in fresh condition on the right. Place the whole row inside approximately x=55..480 and y=50..215 on the 1254-square canvas. The row should be easy to read as a thumbnail but secondary to the fridge and title.
Fruit style: authentic-looking Project Zomboid inventory sprites, low-resolution slightly grainy pixels with visible stepped edges, restrained shading and a simple narrow dark outline. Each apple approximately 125 pixels tall at this canvas resolution. Both apples have the same silhouette, stem, scale and angle. Rotten apple: dull collapsed brown/olive skin, obvious gray-green mold patches and dark soft spots; recognizable as an apple. Fresh apple: healthy clean red skin, intact shape, a short brown stem and a small ordinary green leaf. No exaggerated cartoon faces, no realistic food photography, no polished 3D product rendering, no glow.
Arrow: one modest off-white block/pixel arrow pointing clearly LEFT TO RIGHT, with a thin dark pixel outline for contrast. No curve, no light trail, no glitter. Keep a clear small gap between each icon and the arrow. No backing card or banner and no extra text.

Preserve the entire original image outside these three small added shapes: identical crop and canvas proportions; all existing kitchen tiles, cabinets, refrigerator, character, lighting, colors and textures stay unchanged. Preserve both existing title lines exactly, in their original positions, size and lettering: "冰箱返鲜" and "POWERED FRESHNESS". Do not redraw the kitchen, move anything, open the refrigerator, restyle the image, recolor the scene, alter the typography or introduce another panel. The result must look like the approved image with a simple game-item before/after overlay added.
```
