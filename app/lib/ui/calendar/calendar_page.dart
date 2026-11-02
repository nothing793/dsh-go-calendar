import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/l10n.dart';
import '../../state/calendar_state.dart';
import '../common/app_sheet.dart';
import '../common/brief_notice.dart';
import '../common/list_page_row.dart';
import '../features/feature_kit.dart';
import '../phone/phone_widgets.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';

/// 设置 → 日历: a separate switch for the built-in calendar MCP.
class CalendarPage extends ConsumerWidget {
  const CalendarPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = context.l10n;
    final state = ref.watch(calendarProvider);
    final status = state.status;
    final permissionText = switch (status.permission) {
      'granted' => l10n.calendarPermissionGranted,
      'partial' => l10n.calendarPermissionPartial,
      _ => l10n.calendarPermissionMissing,
    };

    return AppPage.list(
      title: l10n.calendarTitle,
      subtitle: Text(l10n.calendarSubtitle),
      children: [
        if (!state.loaded)
          const QuietSpinner(padding: EdgeInsets.symmetric(vertical: 32))
        else ...[
          ListPageCard(
            children: [
              ListPageRow(
                icon: AppIcons.calendar,
                title: l10n.calendarEnabled,
                subtitle: l10n.calendarEnabledHint,
                trailing: state.busy
                    ? const SizedBox.square(
                        dimension: 20,
                        child: CircularProgressIndicator(strokeWidth: 2, strokeCap: StrokeCap.round),
                      )
                    : Switch(
                        value: status.enabled,
                        onChanged: (value) => unawaited(_toggle(context, ref, value)),
                      ),
              ),
              ListPageRow(
                icon: AppIcons.permissions,
                title: l10n.calendarPermission,
                subtitle: permissionText,
                trailing: status.granted
                    ? DoneMark(label: l10n.calendarPermissionGrantedShort)
                    : FixButton(
                        label: l10n.calendarAllow,
                        onPressed: () => unawaited(_requestPermission(context, ref)),
                      ),
              ),
              ListPageRow(
                icon: AppIcons.calendar,
                title: l10n.calendarChoose,
                subtitle: status.selected == null
                    ? l10n.calendarChooseHint
                    : l10n.calendarSelected(status.selected!.name, status.selected!.account),
                chevron: status.granted && status.calendars.isNotEmpty,
                onTap: status.granted && status.calendars.isNotEmpty
                    ? () => unawaited(_pickCalendar(context, ref, status.calendars))
                    : null,
              ),
            ],
          ),
          if (status.granted && status.calendars.isEmpty)
            const SizedBox(height: 8),
          if (status.granted && status.calendars.isEmpty)
            PageFootnote(l10n.calendarNoCalendars),
          PageFootnote(l10n.calendarFootnote),
        ],
      ],
    );
  }

  Future<void> _toggle(BuildContext context, WidgetRef ref, bool next) async {
    final result = await ref.read(calendarProvider.notifier).setEnabled(next);
    if (!context.mounted) return;
    final l10n = context.l10n;
    final text = !result.ok
        ? (result.reason ?? l10n.calendarSaveFailed)
        : result.restarting
        ? l10n.calendarRestarting
        : next
        ? l10n.calendarEnabledNotice
        : l10n.calendarDisabledNotice;
    showBriefNotice(context, text);
  }

  Future<void> _requestPermission(BuildContext context, WidgetRef ref) async {
    final granted = await ref.read(calendarProvider.notifier).requestPermission();
    if (!context.mounted) return;
    if (!granted) {
      showBriefNotice(context, context.l10n.calendarPermissionDenied);
      await ref.read(calendarProvider.notifier).openAppSettings();
    }
  }

  Future<void> _pickCalendar(BuildContext context, WidgetRef ref, List<CalendarInfo> calendars) async {
    final l10n = context.l10n;
    final picked = await showAppSheet<String>(
      context,
      isScrollControlled: true,
      builder: (sheet) => SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            SheetHeader(l10n.calendarPickerTitle),
            Flexible(
              child: SingleChildScrollView(
                child: SheetGroup(
                  children: [
                    for (final calendar in calendars)
                      ListPageRow(
                        icon: AppIcons.calendar,
                        title: calendar.name,
                        subtitle: calendar.account.isEmpty ? null : calendar.account,
                        trailing: calendar.primary ? Text(l10n.calendarPickerPrimary) : null,
                        onTap: () => Navigator.pop(sheet, calendar.id),
                      ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),
          ],
        ),
      ),
    );
    if (picked == null || !context.mounted) return;
    final ok = await ref.read(calendarProvider.notifier).setCalendar(picked);
    if (!context.mounted) return;
    showBriefNotice(context, ok ? l10n.calendarSelectedNotice : l10n.calendarSaveFailed);
  }
}
