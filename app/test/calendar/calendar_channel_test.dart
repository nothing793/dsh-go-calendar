import 'package:dsh_app/calendar/calendar_channel.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('parses native status, selected calendar and writable list', () async {
    const methods = MethodChannel('test/calendar/status');
    final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(methods, (call) async {
      expect(call.method, 'status');
      return {
        'enabled': true,
        'permission': 'granted',
        'selected': {'id': '1', 'name': '校园', 'account': 'school', 'writable': true, 'primary': true},
        'calendars': [
          {'id': '1', 'name': '校园', 'account': 'school', 'writable': true, 'primary': true},
          {'id': '2', 'name': '个人', 'account': 'me', 'writable': true, 'primary': false},
        ],
        'bridgeListening': true,
        'ready': true,
      };
    });
    addTearDown(() => messenger.setMockMethodCallHandler(methods, null));

    final status = await CalendarChannel(methods: methods).status();
    expect(status.enabled, isTrue);
    expect(status.granted, isTrue);
    expect(status.ready, isTrue);
    expect(status.selected?.name, '校园');
    expect(status.calendars.map((c) => c.id), ['1', '2']);
  });

  test('setEnabled carries a native refusal reason', () async {
    const methods = MethodChannel('test/calendar/refuse');
    final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(methods, (call) async {
      expect(call.method, 'setEnabled');
      expect(call.arguments, {'enabled': true});
      return {'ok': false, 'reason': '请先选择日历'};
    });
    addTearDown(() => messenger.setMockMethodCallHandler(methods, null));

    final result = await CalendarChannel(methods: methods).setEnabled(true);
    expect(result.ok, isFalse);
    expect(result.reason, '请先选择日历');
  });

  test('a missing native module reads as not ready instead of throwing', () async {
    final status = await CalendarChannel(methods: const MethodChannel('test/calendar/missing')).status();
    expect(status.enabled, isFalse);
    expect(status.permission, 'missing');
    expect(status.calendars, isEmpty);
  });
}
