# Avatares: 32 prompts prontos

Etapa 1 do [plano-revisao-avatares.md](plano-revisao-avatares.md). Um prompt por profissão × gênero, montado a partir do uniforme e do acessório desenhados hoje em `ui/persona/PersonaAvatar.kt` (`drawBody` e `drawGear`), no padrão do prompt-modelo do plano. A mesma lista está em [avatares-prompts.csv](avatares-prompts.csv) para geração em lote.

## Como gerar

- **Formato de entrega:** 1024×1024 px, WebP qualidade 90, quadrado, sem moldura nem texto (o app recorta em círculo e aplica o medalhão).
- **Nome do arquivo:** use exatamente o da coluna `arquivo` (ex.: `avatar_doctor_f.webp`). É o nome que vai para `app/src/main/res/drawable-nodpi/` na etapa 2.
- **Prompt negativo** (se a ferramenta aceitar):

  ```
  cartoon, chibi, anime, childlike proportions, big head, text, watermark, logo, frame, border, busy background, scenery, extra fingers, distorted hands, cropped head, multiple people
  ```

- **Consistência da série:** gere uma imagem de teste (sugestão: `avatar_pilot_f`), aprove a luz e o enquadramento e, se a ferramenta permitir, reutilize a mesma semente/estilo de referência nas outras 31.
- **Legibilidade:** confira cada retrato reduzido a 48 px antes de aprovar; o acessório precisa ser reconhecível nesse tamanho.
- **Diversidade:** idade (26–58), tom de pele, cabelo e barba já vêm distribuídos nos prompts; troque à vontade, mantendo a variedade no conjunto.

## Prompts

### Médico / Médica (`DOCTOR`)

**`avatar_doctor_m.webp`** — Médico

```
Semi-realistic 3D character portrait, bust shot, male doctor aged 32, light olive skin, short dark-brown hair, wearing a crisp white doctor's coat over a teal V-neck scrub top, fine gold piping on the lapels, with a stethoscope draped around the neck with a polished silver-and-gold chest piece, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

**`avatar_doctor_f.webp`** — Médica

```
Semi-realistic 3D character portrait, bust shot, female doctor aged 58, deep brown skin, dark hair in a low bun, wearing a crisp white doctor's coat over a teal V-neck scrub top, fine gold piping on the lapels, with a stethoscope draped around the neck with a polished silver-and-gold chest piece, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

### Cozinheiro / Cozinheira (`CHEF`)

**`avatar_chef_m.webp`** — Cozinheiro

```
Semi-realistic 3D character portrait, bust shot, male chef aged 45, medium tan skin, short black curly hair, short well-groomed beard, wearing a white double-breasted chef's jacket with grey buttons and a red neckerchief, gold embroidered trim, with a tall pleated white chef's toque, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

**`avatar_chef_f.webp`** — Cozinheira

```
Semi-realistic 3D character portrait, bust shot, female chef aged 35, fair skin, blonde hair in a ponytail, wearing a white double-breasted chef's jacket with grey buttons and a red neckerchief, gold embroidered trim, with a tall pleated white chef's toque, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

### Engenheiro / Engenheira (`ENGINEER`)

**`avatar_engineer_m.webp`** — Engenheiro

```
Semi-realistic 3D character portrait, bust shot, male engineer aged 28, warm brown skin, neatly side-swept chestnut hair, wearing a slate-grey work shirt under an orange high-visibility vest with reflective yellow stripes and gold buckles, with a glossy yellow hard hat with an amber headlamp at the front, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

**`avatar_engineer_f.webp`** — Engenheira

```
Semi-realistic 3D character portrait, bust shot, female engineer aged 41, light olive skin, long voluminous curly black hair, wearing a slate-grey work shirt under an orange high-visibility vest with reflective yellow stripes and gold buckles, with a glossy yellow hard hat with an amber headlamp at the front, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

### Professor / Professora (`TEACHER`)

**`avatar_teacher_m.webp`** — Professor

```
Semi-realistic 3D character portrait, bust shot, male teacher aged 52, deep brown skin, clean-shaven bald head, wearing a warm brown tailored blazer over a white open-collar shirt, small gold lapel pin, with round black-rimmed reading glasses, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

**`avatar_teacher_f.webp`** — Professora

```
Semi-realistic 3D character portrait, bust shot, female teacher aged 30, medium tan skin, copper-red hair in a neat bun, wearing a warm brown tailored blazer over a white open-collar shirt, small gold lapel pin, with round black-rimmed reading glasses, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

### Bombeiro / Bombeira (`FIREFIGHTER`)

**`avatar_firefighter_m.webp`** — Bombeiro

```
Semi-realistic 3D character portrait, bust shot, male firefighter aged 38, fair skin, short salt-and-pepper hair, short well-groomed beard, wearing a charcoal turnout coat with a bright yellow reflective band across the chest, gold clasps, with a red firefighter helmet with a gold shield emblem on the front, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

**`avatar_firefighter_f.webp`** — Bombeira

```
Semi-realistic 3D character portrait, bust shot, female firefighter aged 47, warm brown skin, long straight black hair, wearing a charcoal turnout coat with a bright yellow reflective band across the chest, gold clasps, with a red firefighter helmet with a gold shield emblem on the front, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

### Cientista / Cientista (`SCIENTIST`)

**`avatar_scientist_m.webp`** — Cientista

```
Semi-realistic 3D character portrait, bust shot, male scientist aged 58, light olive skin, short auburn hair, wearing a white lab coat over a light-blue shirt, red and blue pens in the breast pocket, gold collar detail, with clear safety goggles with cyan lenses resting on the forehead, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

**`avatar_scientist_f.webp`** — Cientista

```
Semi-realistic 3D character portrait, bust shot, female scientist aged 26, deep brown skin, shoulder-length chestnut bob, wearing a white lab coat over a light-blue shirt, red and blue pens in the breast pocket, gold collar detail, with clear safety goggles with cyan lenses resting on the forehead, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

### Piloto / Piloto (`PILOT`)

**`avatar_pilot_m.webp`** — Piloto

```
Semi-realistic 3D character portrait, bust shot, male pilot aged 35, medium tan skin, short dark-brown hair, wearing a navy-blue airline captain's uniform with white shirt, black tie and gold epaulettes, with a navy captain's cap with a black visor and a gold wings badge, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

**`avatar_pilot_f.webp`** — Piloto

```
Semi-realistic 3D character portrait, bust shot, female pilot aged 55, fair skin, dark hair in a low bun, wearing a navy-blue airline captain's uniform with white shirt, black tie and gold epaulettes, with a navy captain's cap with a black visor and a gold wings badge, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

### Pintor / Pintora (`ARTIST`)

**`avatar_artist_m.webp`** — Pintor

```
Semi-realistic 3D character portrait, bust shot, male artist aged 41, warm brown skin, short black curly hair, short well-groomed beard, wearing an off-white painter's smock with small colorful paint splashes (yellow, red, green, blue), gold buttons, with a red beret tilted to one side, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

**`avatar_artist_f.webp`** — Pintora

```
Semi-realistic 3D character portrait, bust shot, female artist aged 36, light olive skin, blonde hair in a ponytail, wearing an off-white painter's smock with small colorful paint splashes (yellow, red, green, blue), gold buttons, with a red beret tilted to one side, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

### Enfermeiro / Enfermeira (`NURSE`)

**`avatar_nurse_m.webp`** — Enfermeiro

```
Semi-realistic 3D character portrait, bust shot, male nurse aged 30, deep brown skin, neatly side-swept chestnut hair, wearing light-blue nurse scrubs with a white V-neck and a navy breast-pocket badge, gold piping, with a light-blue scrub cap with a small white cross, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

**`avatar_nurse_f.webp`** — Enfermeira

```
Semi-realistic 3D character portrait, bust shot, female nurse aged 43, medium tan skin, long voluminous curly black hair, wearing light-blue nurse scrubs with a white V-neck and a navy breast-pocket badge, gold piping, with a light-blue scrub cap with a small white cross, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

### Policial / Policial (`POLICE`)

**`avatar_police_m.webp`** — Policial

```
Semi-realistic 3D character portrait, bust shot, male police aged 47, fair skin, clean-shaven bald head, wearing a dark navy police uniform shirt, white collar, black tie and a gold badge on the chest, with a navy police peaked cap with a black visor and gold insignia, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

**`avatar_police_f.webp`** — Policial

```
Semi-realistic 3D character portrait, bust shot, female police aged 33, warm brown skin, copper-red hair in a neat bun, wearing a dark navy police uniform shirt, white collar, black tie and a gold badge on the chest, with a navy police peaked cap with a black visor and gold insignia, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

### Agricultor / Agricultora (`FARMER`)

**`avatar_farmer_m.webp`** — Agricultor

```
Semi-realistic 3D character portrait, bust shot, male farmer aged 26, light olive skin, short salt-and-pepper hair, short well-groomed beard, wearing a red plaid shirt under blue denim overalls with gold buttons, with a wide-brimmed straw hat with a brown leather band, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

**`avatar_farmer_f.webp`** — Agricultora

```
Semi-realistic 3D character portrait, bust shot, female farmer aged 50, deep brown skin, long straight black hair, wearing a red plaid shirt under blue denim overalls with gold buttons, with a wide-brimmed straw hat with a brown leather band, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

### Mecânico / Mecânica (`MECHANIC`)

**`avatar_mechanic_m.webp`** — Mecânico

```
Semi-realistic 3D character portrait, bust shot, male mechanic aged 55, medium tan skin, short auburn hair, wearing a royal-blue zip-up mechanic coverall with a white name patch and a gold zipper pull, a faint grease smudge on the cheek, with a red baseball cap worn slightly to the side, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

**`avatar_mechanic_f.webp`** — Mecânica

```
Semi-realistic 3D character portrait, bust shot, female mechanic aged 32, fair skin, shoulder-length chestnut bob, wearing a royal-blue zip-up mechanic coverall with a white name patch and a gold zipper pull, a faint grease smudge on the cheek, with a red baseball cap worn slightly to the side, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

### Músico / Música (`MUSICIAN`)

**`avatar_musician_m.webp`** — Músico

```
Semi-realistic 3D character portrait, bust shot, male musician aged 36, warm brown skin, short dark-brown hair, wearing a deep purple velvet jacket over a white shirt, small gold musical-note pin, with black studio headphones with orange ear cups resting over the ears, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

**`avatar_musician_f.webp`** — Música

```
Semi-realistic 3D character portrait, bust shot, female musician aged 45, light olive skin, dark hair in a low bun, wearing a deep purple velvet jacket over a white shirt, small gold musical-note pin, with black studio headphones with orange ear cups resting over the ears, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

### Advogado / Advogada (`LAWYER`)

**`avatar_lawyer_m.webp`** — Advogado

```
Semi-realistic 3D character portrait, bust shot, male lawyer aged 43, deep brown skin, short black curly hair, short well-groomed beard, wearing a dark charcoal tailored suit, white shirt and a crimson tie, gold cufflinks, with a slim gold pen visible in the breast pocket, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

**`avatar_lawyer_f.webp`** — Advogada

```
Semi-realistic 3D character portrait, bust shot, female lawyer aged 28, medium tan skin, blonde hair in a ponytail, wearing a dark charcoal tailored suit, white shirt and a crimson tie, gold cufflinks, with a slim gold pen visible in the breast pocket, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

### Fotógrafo / Fotógrafa (`PHOTOGRAPHER`)

**`avatar_photographer_m.webp`** — Fotógrafo

```
Semi-realistic 3D character portrait, bust shot, male photographer aged 33, fair skin, neatly side-swept chestnut hair, wearing a light taupe field jacket with a brown leather camera strap across the chest, with a professional black camera held at chest height and a grey cap, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

**`avatar_photographer_f.webp`** — Fotógrafa

```
Semi-realistic 3D character portrait, bust shot, female photographer aged 52, warm brown skin, long voluminous curly black hair, wearing a light taupe field jacket with a brown leather camera strap across the chest, with a professional black camera held at chest height and a grey cap, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

### Marinheiro / Marinheira (`SAILOR`)

**`avatar_sailor_m.webp`** — Marinheiro

```
Semi-realistic 3D character portrait, bust shot, male sailor aged 50, light olive skin, clean-shaven bald head, wearing a white sailor shirt with navy-blue horizontal stripes and a blue neckerchief, gold anchor pin, with a white sailor cap with a navy band, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

**`avatar_sailor_f.webp`** — Marinheira

```
Semi-realistic 3D character portrait, bust shot, female sailor aged 38, deep brown skin, copper-red hair in a neat bun, wearing a white sailor shirt with navy-blue horizontal stripes and a blue neckerchief, gold anchor pin, with a white sailor cap with a navy band, calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue (#0B1026) to indigo (#2A3290) gradient background with soft gold halo behind the head, premium mobile game avatar, high detail, centered, square, face about 45% of the frame
```

## Checklist de entrega

- [ ] `avatar_doctor_m.webp`
- [ ] `avatar_doctor_f.webp`
- [ ] `avatar_chef_m.webp`
- [ ] `avatar_chef_f.webp`
- [ ] `avatar_engineer_m.webp`
- [ ] `avatar_engineer_f.webp`
- [ ] `avatar_teacher_m.webp`
- [ ] `avatar_teacher_f.webp`
- [ ] `avatar_firefighter_m.webp`
- [ ] `avatar_firefighter_f.webp`
- [ ] `avatar_scientist_m.webp`
- [ ] `avatar_scientist_f.webp`
- [ ] `avatar_pilot_m.webp`
- [ ] `avatar_pilot_f.webp`
- [ ] `avatar_artist_m.webp`
- [ ] `avatar_artist_f.webp`
- [ ] `avatar_nurse_m.webp`
- [ ] `avatar_nurse_f.webp`
- [ ] `avatar_police_m.webp`
- [ ] `avatar_police_f.webp`
- [ ] `avatar_farmer_m.webp`
- [ ] `avatar_farmer_f.webp`
- [ ] `avatar_mechanic_m.webp`
- [ ] `avatar_mechanic_f.webp`
- [ ] `avatar_musician_m.webp`
- [ ] `avatar_musician_f.webp`
- [ ] `avatar_lawyer_m.webp`
- [ ] `avatar_lawyer_f.webp`
- [ ] `avatar_photographer_m.webp`
- [ ] `avatar_photographer_f.webp`
- [ ] `avatar_sailor_m.webp`
- [ ] `avatar_sailor_f.webp`
