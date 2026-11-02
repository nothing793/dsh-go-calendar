// Dart side of the built-in `dsh/calendar` MethodChannel. The native side is
// CalendarPlugin.kt; every call tolerates the module being absent.

import 'package:flutter/services.dart';

Map<Object?, Object?> _map(Object? value) => value is Map ? value.cast<Object?, Object?>() : const {};

String? _text(Object? value) => value is String && value.trim().isNotEmpty ? value.trim() : null;

bool _flag(Object? value) => value == true;

class CalendarInfo {
  const CalendarInfo({
    required this.id,
    required this.name,
    required this.account,
    required this.primary,
  });

  factory CalendarInfo.fromMap(Map<Object?, Object?> m) => CalendarInfo(
    id: _text(m['id']) ?? '',
    name: _text(m['name']) ?? '',
    account: _text(m['account']) ?? '',
    primary: _flag(m['primary']),
  );

  final String id;
  final String name;
  final String account;
  final bool primary;
}

class CalendarStatus {
  const CalendarStatus({
    this.enabled = false,
    this.permission = 'missing',
    this.selected,
    this.calendars = const [],
    this.bridgeListening = false,
    this.ready = false,
  });

  factory CalendarStatus.fromMap(Map<Object?, Object?> m) {
    final raw = m['calendars'];
    return CalendarStatus(
      enabled: _flag(m['enabled']),
      permission: _text(m['permission']) ?? 'missing',
      selected: m['selected'] is Map ? CalendarInfo.fromMap(_map(m['selected'])) : null,
      calendars: raw is List
          ? [
              for (final item in raw)
                if (item is Map) CalendarInfo.fromMap(item.cast<Object?, Object?>()),
            ]
          : const [],
      bridgeListening: _flag(m['bridgeListening']),
      ready: _flag(m['ready']),
    );
  }

  final bool enabled;

  /// `granted`, `partial`, or `missing`.
  final String permission;
  final CalendarInfo? selected;
  final List<CalendarInfo> calendars;
  final bool bridgeListening;
  final bool ready;

  bool get granted => permission == 'granted';
}

class CalendarResult {
  const CalendarResult(this.ok, [this.reason]);

  factory CalendarResult.fromMap(Map<Object?, Object?> m) =>
      CalendarResult(_flag(m['ok']), _text(m['reason']));

  final bool ok;
  final String? reason;
}

class CalendarChannel {
  CalendarChannel({MethodChannel? methods}) : _methods = methods ?? const MethodChannel('dsh/calendar');

  final MethodChannel _methods;

  Future<CalendarStatus> status() async {
    try {
      final map = await _methods.invokeMapMethod<Object?, Object?>('status');
      return map == null ? const CalendarStatus() : CalendarStatus.fromMap(map);
    } on MissingPluginException {
      return const CalendarStatus();
    } on PlatformException {
      return const CalendarStatus();
    }
  }

  Future<CalendarResult> setEnabled(bool enabled) async {
    try {
      final map = await _methods.invokeMapMethod<Object?, Object?>('setEnabled', {'enabled': enabled});
      return map == null ? const CalendarResult(false) : CalendarResult.fromMap(map);
    } on MissingPluginException {
      return const CalendarResult(false);
    } on PlatformException catch (e) {
      return CalendarResult(false, e.message);
    }
  }

  Future<bool> requestPermission() async {
    try {
      return await _methods.invokeMethod<bool>('requestPermission') ?? false;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  Future<List<CalendarInfo>> calendars() async {
    try {
      final list = await _methods.invokeListMethod<Object?>('calendars');
      return [
        for (final item in list ?? const <Object?>[])
          if (item is Map) CalendarInfo.fromMap(item.cast<Object?, Object?>()),
      ];
    } on MissingPluginException {
      return const [];
    } on PlatformException {
      return const [];
    }
  }

  Future<bool> setCalendar(String id) async {
    try {
      final map = await _methods.invokeMapMethod<Object?, Object?>('setCalendar', {'id': id});
      return map != null && map['ok'] == true;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  Future<bool> openAppSettings() async {
    try {
      return await _methods.invokeMethod<bool>('openAppSettings') ?? false;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }
}
