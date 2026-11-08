import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'dart:async';

void main() => runApp(const VirtualBikeApp());

class VirtualBikeApp extends StatelessWidget {
  const VirtualBikeApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    title: 'Virtual FTMS Bike',
    theme: ThemeData.dark(useMaterial3: true),
    home: const BikeHome(),
  );
}

class BikeHome extends StatefulWidget {
  const BikeHome({super.key});
  @override
  State<BikeHome> createState() => _BikeHomeState();
}

class _BikeHomeState extends State<BikeHome> {
  static const channel = MethodChannel('virtual_ftms_bike/ble');
  bool running = false;
  List<String> bleLogs = [];
  Timer? logTimer;
  @override
  void initState() {
    super.initState();
    logTimer = Timer.periodic(const Duration(seconds: 1), (_) => refreshLogs());
  }
  Future<void> refreshLogs() async {
    try {
      final lines = await channel.invokeListMethod<String>('logs');
      if (mounted && lines != null) setState(() => bleLogs = lines);
    } catch (_) {}
  }
  @override
  void dispose() { logTimer?.cancel(); super.dispose(); }

  String status = 'Parado';
  double speed = 20;
  double cadence = 60;
  double power = 100;

  Future<void> toggle() async {
    try {
      if (running) {
        await channel.invokeMethod('stop');
        setState(() { running = false; status = 'Parado'; });
      } else {
        final result = await channel.invokeMethod<String>('start', {
          'speed': speed, 'cadence': cadence, 'power': power.round(),
        });
        setState(() { running = true; status = result ?? 'A anunciar'; });
      }
    } on PlatformException catch (e) {
      setState(() => status = 'Erro: ${e.message ?? e.code}');
    }
  }

  Future<void> update() async {
    if (!running) return;
    try {
      await channel.invokeMethod('update', {
        'speed': speed, 'cadence': cadence, 'power': power.round(),
      });
    } on PlatformException catch (e) {
      setState(() => status = 'Erro: ${e.message ?? e.code}');
    }
  }

  Widget metric(String title, double value, double min, double max,
      int divisions, String unit, ValueChanged<double> onChanged) {
    return Card(child: Padding(
      padding: const EdgeInsets.all(12),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('$title: ${value.toStringAsFixed(title == 'Velocidade' ? 1 : 0)} $unit',
            style: const TextStyle(fontSize: 18)),
        Slider(value: value, min: min, max: max, divisions: divisions,
          onChanged: (v) { setState(() => onChanged(v)); update(); }),
      ]),
    ));
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Virtual FTMS Bike')),
    body: SafeArea(child: ListView(padding: const EdgeInsets.all(16), children: [
      const Icon(Icons.directions_bike, size: 70, color: Colors.tealAccent),
      const SizedBox(height: 12),
      const Text('Bicicleta BLE virtual para testar o Incyclist', textAlign: TextAlign.center),
      const SizedBox(height: 18),
      metric('Velocidade', speed, 0, 60, 120, 'km/h', (v) => speed = v),
      metric('Cadência', cadence, 0, 140, 140, 'RPM', (v) => cadence = v),
      metric('Potência', power, 0, 500, 100, 'W', (v) => power = v),
      const SizedBox(height: 12),
      Text('Estado: $status', textAlign: TextAlign.center),
      const SizedBox(height: 12),
      FilledButton.icon(onPressed: toggle,
        icon: Icon(running ? Icons.stop : Icons.bluetooth),
        label: Text(running ? 'Parar bicicleta virtual' : 'Iniciar bicicleta virtual')),
      const SizedBox(height: 18),
      Row(children: [
        const Expanded(child: Text('Diagnóstico Bluetooth', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold))),
        TextButton(onPressed: () async { await channel.invokeMethod('clearLogs'); await refreshLogs(); }, child: const Text('Limpar')),
        TextButton(onPressed: () { Clipboard.setData(ClipboardData(text: bleLogs.join('\n'))); ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Registos copiados'))); }, child: const Text('Copiar')),
      ]),
      Container(height: 230, padding: const EdgeInsets.all(10), color: Colors.black54,
        child: ListView(children: [SelectableText(bleLogs.isEmpty ? 'Sem eventos ainda' : bleLogs.join('\n'),
          style: const TextStyle(fontSize: 11, fontFamily: 'monospace'))])),
      const SizedBox(height: 20),
      const Text('No tablet, procura «Virtual FTMS Bike» no Incyclist. '
          'Mantém esta aplicação aberta durante o teste. '
          'Esta versão não lê ainda os dados da Cecotec.'),
    ])),
  );
}
