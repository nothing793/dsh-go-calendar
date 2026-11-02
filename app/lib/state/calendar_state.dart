import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../calendar/calendar_channel.dart';
import '../l10n/l10n.dart';
import '../runtime/runtime_bridge.dart';
import '../runtime/runtime_state.dart';
import 'engine_control.dart';

export '../calendar/calendar_channel.dart';

final calendarChannelProvider = Provider<CalendarChannel>((ref) => CalendarChannel());
final calendarProvider = NotifierProvider<CalendarController, CalendarState>(CalendarController.new);

class CalendarState {
  const CalendarState({
    this.status = const CalendarStatus(),
    this.loaded = false,
    this.busy = false,
  });

  final CalendarStatus status;
  final bool loaded;
  final bool busy;

  CalendarState copyWith({CalendarStatus? status, bool? loaded, bool? busy}) => CalendarState(
    status: status ?? this.status,
    loaded: loaded ?? this.loaded,
    busy: busy ?? this.busy,
  );
}

class CalendarToggleResult {
  const CalendarToggleResult({required this.ok, this.restarting = false, this.reason});

  final bool ok;
  final bool restarting;
  final String? reason;
}

class CalendarController extends Notifier<CalendarState> {
  CalendarChannel get _channel => ref.read(calendarChannelProvider);

  @override
  CalendarState build() {
    unawaited(refresh());
    return const CalendarState();
  }

  Future<void> refresh() async {
    final status = await _channel.status();
    if (!ref.mounted) return;
    state = state.copyWith(status: status, loaded: true);
  }

  Future<bool> requestPermission() async {
    final granted = await _channel.requestPermission();
    if (ref.mounted) await refresh();
    return granted;
  }

  Future<List<CalendarInfo>> calendars() => _channel.calendars();

  Future<bool> setCalendar(String id) async {
    final ok = await _channel.setCalendar(id);
    if (ref.mounted && ok) await refresh();
    return ok;
  }

  Future<CalendarToggleResult> setEnabled(bool enabled) async {
    if (state.busy) return const CalendarToggleResult(ok: false);
    state = state.copyWith(busy: true);
    var restarting = false;
    try {
      if (enabled && !state.status.granted) {
        final granted = await requestPermission();
        if (!granted) {
          return CalendarToggleResult(ok: false, reason: appL10n.calendarPermissionDenied);
        }
      }
      final result = await _channel.setEnabled(enabled);
      if (!ref.mounted) return CalendarToggleResult(ok: result.ok, reason: result.reason);
      if (!result.ok) return CalendarToggleResult(ok: false, reason: result.reason);
      await refresh();
      final now = ref.read(localRuntimeProvider).status;
      if (!now.inSafeMode && (now.phase == RuntimePhase.running || now.phase == RuntimePhase.starting)) {
        restarting = true;
        unawaited(EngineControl.restart(ref.container));
      }
      return CalendarToggleResult(ok: true, restarting: restarting);
    } finally {
      if (ref.mounted) state = state.copyWith(busy: false);
    }
  }

  Future<bool> openAppSettings() => _channel.openAppSettings();
}
