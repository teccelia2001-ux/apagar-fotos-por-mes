# Apagar Fotos por Mês

App Android que mostra as fotos e vídeos do celular separados por mês e apaga os meses escolhidos.

- Filtra por **fotos**, **vídeos** ou ambos, e por **pasta** (Câmera, WhatsApp Images, Screenshots…).
- Marque meses inteiros (ou o ano todo) e toque em **Apagar**.
- Toque num mês para ver as miniaturas; toque numa foto para **manter** ela.
- No Android 11 ou mais novo os itens vão para a **lixeira** (dá para restaurar pelo botão Lixeira; o Android apaga de vez em 30 dias).
- O Android sempre pede uma confirmação própria antes de apagar.

## Instalar

No celular, abra o link abaixo, baixe o `ApagarFotosPorMes.apk` e instale
(o Android vai pedir para permitir instalar de "fontes desconhecidas" pelo navegador):

https://github.com/teccelia2001-ux/apagar-fotos-por-mes/releases/latest/download/ApagarFotosPorMes.apk

## Como é compilado

Não precisa de Android Studio. A cada `git push` na `main`, o GitHub Actions
(`.github/workflows/compilar.yml`) compila o APK e publica uma nova versão em *Releases*.
A chave de assinatura fica nos secrets `KEYSTORE_B64` e `KEYSTORE_PASS` do repositório —
sem ela as versões novas não instalam por cima da antiga.
