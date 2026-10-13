# Minecraft LinkBlocks

**Projeto em desenvolvimento:** aplicativo Android + add-on para controlar blocos/regras no Minecraft Bedrock usando o celular. O GitHub Actions compila o APK e empacota o add-on automaticamente.

## Recursos desta primeira versão

- App Android nativo em Kotlin, com conexão por Wi-Fi/LAN e Bluetooth Classic via gateway Linux.
- Regras nomeadas por você, como `portao` ou `luzes_sala`.
- Gatilhos: botão **Acionar agora**, temporizador em segundos e borda de subida de um sinal de redstone.
- Ações: abrir/fechar porta, alternar lâmpada de redstone, executar comando de Minecraft, enviar mensagem, mudar para dia/noite e limpar o clima.
- Bloco visual customizado `linkblocks:controller`.
- Ponte local Node.js com fila de comandos, persistência das regras e token de acesso.

## Baixar o APK e o add-on

Abra [GitHub Actions](https://github.com/nejo27275-maker/Meusite/actions), selecione a execução mais recente de **Build Android APK + Bedrock Add-On** e baixe o artefato **minecraft-linkblocks-build**. Ele contém:

- `Minecraft-LinkBlocks-debug.apk` — APK de teste para instalar no Android.
- `minecraft-linkblocks.mcaddon` — pacote do add-on.
- `SHA256SUMS.txt` — hashes para conferir os arquivos.

O APK é uma versão **debug**, sem assinatura de distribuição. O workflow só consegue fornecer o arquivo depois que a execução terminar com sucesso.

## Compatibilidade importante

O controlador externo usa a API oficial `@minecraft/server-net`. A Microsoft documenta que essa API funciona **somente no Bedrock Dedicated Server** e não no cliente de jogo normal nem no Realms. A versão usada aqui é preview/beta (`1.0.0-beta.1.26.60-preview.30`), portanto precisa de um ambiente compatível com essa API. Um mundo solo aberto no Minecraft Android não consegue receber esses comandos diretamente pelo app.

Documentação oficial: [@minecraft/server-net](https://learn.microsoft.com/en-us/minecraft/creator/scriptapi/minecraft/server-net/minecraft-server-net?view=minecraft-bedrock-experimental) · [Block.getRedstonePower](https://learn.microsoft.com/en-us/minecraft/creator/scriptapi/minecraft/server/block?view=minecraft-bedrock-stable).

## Configurar a ponte

Execute a ponte no mesmo computador que executa o Bedrock Dedicated Server (ou altere a URL do add-on para apontar ao computador correto). Requer Node.js 20+.

Linux/macOS:
```bash
cd bridge
LINKBLOCKS_TOKEN='troque-por-uma-chave-longa' node server.mjs
```

Windows PowerShell:
```powershell
cd bridge
$env:LINKBLOCKS_TOKEN = "troque-por-uma-chave-longa"
node server.mjs
```

Verifique no navegador do computador se `http://127.0.0.1:8765/health` retorna `ok: true`. No app, informe o IP local do computador (por exemplo, `192.168.1.20`), a porta `8765` e exatamente a mesma chave. O token padrão é apenas para testes: troque-o antes de usar.

Para usar uma chave personalizada, atualize as três configurações: variável `LINKBLOCKS_TOKEN` da ponte, campo **Chave de acesso** no app e constante `TOKEN` em `addon/behavior_pack/scripts/main.js`; depois gere/importe novamente o `.mcaddon`. A constante `BRIDGE_URL` do add-on é `http://127.0.0.1:8765` por padrão.

## Instalar o add-on no Bedrock Dedicated Server

1. Baixe o `.mcaddon` do artefato e extraia-o como ZIP; ele contém as pastas `behavior_pack` e `resource_pack`.
2. Copie essas pastas para as pastas correspondentes `behavior_packs` e `resource_packs` do mundo/servidor, usando nomes próprios se necessário.
3. Ative o pacote de comportamento e o pacote de recursos no mundo do servidor, incluindo os requisitos experimentais/preview pedidos pela versão do Minecraft instalada.
4. Confirme que o servidor consegue carregar os dois manifests e o módulo `@minecraft/server-net`; consulte os logs do servidor em caso de incompatibilidade de versão.
5. Mantenha a ponte e o Bedrock Dedicated Server ativos. Abra o app e toque em **Testar conexão**.

O bloco visual pode ser identificado como `linkblocks:controller`. O campo **Nome lógico do bloco/regra** é o identificador que liga a configuração do app à automação no servidor. Os campos **Bloco monitorado** definem onde observar redstone; **Alvo da ação** define qual bloco a ação modifica.

### Gatilhos e ações

- **Botão no app:** salve a regra e toque em **Acionar agora**.
- **Temporizador:** escolha um intervalo em segundos; a regra começa a disparar após o primeiro intervalo.
- **Sinal de redstone:** informe as coordenadas de um bloco que retorna a energia de redstone; a regra dispara quando o valor muda de sem energia para energizado.
- **Portas/lâmpadas:** as coordenadas do alvo precisam apontar para uma porta compatível ou uma lâmpada de redstone.
- **Executar comando:** escreva o comando sem precisar iniciar com `/`. Comandos executam com as permissões disponíveis para o script do servidor.

## Bluetooth opcional (Linux com BlueZ)

Bluetooth encaminha a mensagem do app à mesma ponte; não substitui o servidor dedicado. O computador precisa estar pareado com o celular e ter suporte a Bluetooth Classic RFCOMM. Em Linux/BlueZ, com o serviço Serial Port Profile disponível, execute:

```bash
sudo sdptool add SP
cd bridge
python3 bluetooth_gateway.py
```

No app, escolha o modo **Bluetooth**, toque em **Escolher dispositivo Bluetooth pareado** e selecione o computador. Se o Bluetooth não estiver configurado no computador, use Wi-Fi/LAN.

## Segurança

Use uma chave longa e exclusiva. Não encaminhe a porta 8765 no roteador e não exponha a ponte à internet; ela foi pensada para uso em rede local. A ponte guarda regras e comandos pendentes em `bridge/state.json`, arquivo ignorado pelo Git.

## Desenvolvimento

- Android: Gradle 8.9, JDK 17, Android SDK 35.
- Ponte: módulos integrados do Node.js.
- Add-on: Script API do Bedrock, com rede HTTP de preview.
- Build: [workflow do GitHub Actions](https://github.com/nejo27275-maker/Meusite/actions).
