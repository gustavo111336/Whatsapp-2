Zap2 - Guia rápido para gerar e baixar o APK pelo GitHub Actions

1. Enviar para o GitHub
- No AI Studio, utilize "Push to GitHub" para enviar o projeto ao seu repositório.
- O workflow fica em: .github/workflows/build-apk.yml

2. Configurar os Secrets no GitHub (necessário para o build e o Login Google/Firebase)
Como o AI Studio não envia app/google-services.json nem a debug.keystore para o GitHub, configure dois secrets no seu repositório:
- Acesse no GitHub: Settings -> Secrets and variables -> Actions -> New repository secret
  1) GOOGLE_SERVICES_JSON: conteúdo do arquivo app/google-services.json
  2) DEBUG_KEYSTORE_BASE64: conteúdo de debug.keystore.base64 (para manter o SHA-1 registrado no Firebase)

3. Gerar e baixar o APK pelo celular
- No repositório do GitHub, abra a aba Actions.
- Selecione "Build Debug APK" (ou toque em "Re-run all jobs" / "Run workflow").
- Quando finalizar com status verde, abra a execução, role até "Artifacts" e baixe "Zap2-debug-apk".
- Extraia o .zip no celular e instale o app-debug.apk.

Observações sobre o Firebase
- Projeto Firebase: gen-lang-client-0742244432
- Produtos configurados automaticamente: Firebase Auth (Google Sign-In) e Cloud Firestore.
- Produtos extras que exigem ativação manual no Console do Firebase (se usados): Phone Auth (SMS), Firebase Storage e FCM.
