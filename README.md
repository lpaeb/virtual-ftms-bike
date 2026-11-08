# Virtual FTMS Bike — protótipo Flutter + Kotlin

Aplicação Android que anuncia um dispositivo BLE FTMS virtual com velocidade, cadência e potência simuladas.

## Compilar sem Android Studio (GitHub Actions)

1. Cria um repositório GitHub vazio, por exemplo `virtual-ftms-bike`.
2. Descompacta o ZIP e envia **o conteúdo** da pasta `virtual_ftms_bike` para a raiz do repositório, incluindo `.github/workflows/android-apk.yml` (pasta oculta).
3. Abre **Actions > Build Android APK > Run workflow**.
4. No fim, descarrega o artifact `virtual-ftms-bike-debug-apk`, extrai o ZIP e instala `app-debug.apk` no POCO F6 Pro.
5. Abre a aplicação, concede as permissões Bluetooth, toca em **Iniciar bicicleta virtual**. Se as permissões foram solicitadas, toca novamente.
6. No tablet, abre o Incyclist e procura `Virtual FTMS Bike`.

## O que testa

- Publicidade BLE com UUID FTMS `0x1826`.
- Servidor GATT com Fitness Machine Feature (`0x2ACC`), Indoor Bike Data (`0x2AD2`), Control Point (`0x2AD9`) e Fitness Machine Status (`0x2ADA`).
- Notificações periódicas com velocidade, cadência, potência e tempo decorrido.
- Respostas básicas ao Control Point.

## Limitações

- **Protótipo experimental, não testado em hardware**. O Incyclist pode exigir mais funcionalidades FTMS e recusar a ligação.
- Não lê a Cecotec; valores simulados ajustáveis nos sliders.
- Mantém a aplicação aberta durante o teste (não há serviço Android em primeiro plano).
- Pode ser necessário desligar temporariamente a ligação Bluetooth direta da bicicleta ao tablet.
- O nome anunciado pode depender do fabricante/versão Android. O código tenta alterar o nome Bluetooth local; isso afeta o nome do telemóvel e pode ser bloqueado pelo sistema.
- APK debug apenas para testes; não distribuir como versão de produção.
