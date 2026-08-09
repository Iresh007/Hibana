package com.opennovel.reader.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.opennovel.reader.data.ReaderSettings
import com.opennovel.reader.data.SettingsRepository
import com.opennovel.reader.data.UpdateSchedule
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Translates the user's chosen cadence into WorkManager requests.
 *
 * Two shapes are used deliberately:
 *  - **Interval schedules** (6h/12h/24h/alternate day) → periodic work. Cheap and
 *    battery-friendly; the OS batches them and exact firing time doesn't matter.
 *  - **Fixed-time schedules** (weekly on a day, monthly on a date) → one-shot work
 *    with a computed initial delay, re-armed by the worker. Periodic work cannot
 *    target a wall-clock time, so it would drift off the requested hour.
 *
 * Both are unique-named so re-applying settings replaces rather than stacks.
 */
object UpdateScheduler {

    suspend fun apply(context: Context, settings: SettingsRepository) {
        apply(context, settings.settings.first())
    }

    fun apply(context: Context, s: ReaderSettings) {
        val wm = WorkManager.getInstance(context)
        // Always clear both shapes so switching cadence never leaves a stale job.
        wm.cancelUniqueWork(LibraryUpdateWorker.PERIODIC_NAME)
        wm.cancelUniqueWork(LibraryUpdateWorker.ONE_SHOT_NAME)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (s.updateOnWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .build()

        when (s.updateSchedule) {
            UpdateSchedule.MANUAL -> Unit

            UpdateSchedule.EVERY_6_HOURS -> enqueuePeriodic(wm, 6, constraints)
            UpdateSchedule.EVERY_12_HOURS -> enqueuePeriodic(wm, 12, constraints)
            UpdateSchedule.DAILY -> enqueuePeriodic(wm, 24, constraints)
            UpdateSchedule.ALTERNATE_DAY -> enqueuePeriodic(wm, 48, constraints)

            UpdateSchedule.WEEKLY, UpdateSchedule.MONTHLY ->
                enqueueOneShot(wm, delayMillis(s), constraints)
        }
    }

    /** Re-arms fixed-time schedules after a run; no-op for interval schedules. */
    suspend fun rescheduleIfOneShot(context: Context, settings: SettingsRepository) {
        val s = settings.settings.first()
        if (s.updateSchedule == UpdateSchedule.WEEKLY || s.updateSchedule == UpdateSchedule.MONTHLY) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (s.updateOnWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
                )
                .build()
            enqueueOneShot(WorkManager.getInstance(context), delayMillis(s), constraints)
        }
    }

    private fun enqueuePeriodic(wm: WorkManager, hours: Long, constraints: Constraints) {
        val request = PeriodicWorkRequestBuilder<LibraryUpdateWorker>(hours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(
            LibraryUpdateWorker.PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun enqueueOneShot(wm: WorkManager, delay: Long, constraints: Constraints) {
        val request = OneTimeWorkRequestBuilder<LibraryUpdateWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniqueWork(
            LibraryUpdateWorker.ONE_SHOT_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /** Milliseconds until the next configured weekly/monthly occurrence. */
    private fun delayMillis(s: ReaderSettings): Long {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, s.updateHour)
            set(Calendar.MINUTE, s.updateMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            when (s.updateSchedule) {
                UpdateSchedule.WEEKLY -> {
                    // Calendar weeks start on Sunday; settings use 1=Mon..7=Sun.
                    set(Calendar.DAY_OF_WEEK, isoToCalendarDay(s.updateDayOfWeek))
                    if (before(now) || equals(now)) add(Calendar.WEEK_OF_YEAR, 1)
                }
                UpdateSchedule.MONTHLY -> {
                    set(Calendar.DAY_OF_MONTH, s.updateDayOfMonth.coerceIn(1, 28))
                    if (before(now) || equals(now)) add(Calendar.MONTH, 1)
                }
                else -> if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return (next.timeInMillis - now.timeInMillis).coerceAtLeast(60_000L)
    }

    private fun isoToCalendarDay(iso: Int): Int = when (iso) {
        1 -> Calendar.MONDAY
        2 -> Calendar.TUESDAY
        3 -> Calendar.WEDNESDAY
        4 -> Calendar.THURSDAY
        5 -> Calendar.FRIDAY
        6 -> Calendar.SATURDAY
        else -> Calendar.SUNDAY
    }
}
