/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */

package com.android.systemui.statusbar.notification.icon.domain.interactor

import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.NotificationManager.IMPORTANCE_MIN
import android.content.applicationContext
import android.graphics.drawable.Icon
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryBypassRepository
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.shared.settings.data.repository.secureSettingsRepository
import com.android.systemui.statusbar.data.repository.notificationListenerSettingsRepository
import com.android.systemui.statusbar.notification.data.model.activeNotificationModel
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationsStore
import com.android.systemui.statusbar.notification.data.repository.activeNotificationListRepository
import com.android.systemui.statusbar.notification.data.repository.getPipelineModels
import com.android.systemui.statusbar.notification.data.repository.getPopulatedActiveNotificationsStore
import com.android.systemui.statusbar.notification.data.repository.notificationsKeyguardViewStateRepository
import com.android.systemui.statusbar.notification.promoted.domain.interactor.aodPromotedNotificationInteractor
import com.android.systemui.statusbar.notification.shared.ActiveBundleModel
import com.android.systemui.statusbar.notification.shared.ActiveNotificationGroupModel
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import com.android.systemui.statusbar.notification.shared.ActivePipelineEntryModel
import com.android.systemui.statusbar.notification.shared.byAssociatedNotifModel
import com.android.systemui.statusbar.notification.shared.byIconIsAmbient
import com.android.systemui.statusbar.notification.shared.byIconNotifKey
import com.android.systemui.statusbar.notification.stack.domain.interactor.notificationsKeyguardInteractor
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.eq
import com.android.wm.shell.bubbles.bubbles
import com.android.wm.shell.bubbles.bubblesOptional
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class NotificationIconsInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope
        get() = kosmos.testScope

    private val activeNotificationListRepository
        get() = kosmos.activeNotificationListRepository

    private val notificationsKeyguardInteractor
        get() = kosmos.notificationsKeyguardInteractor

    private val underTest =
        NotificationIconsInteractor(
            kosmos.activeNotificationListRepository,
            kosmos.bubblesOptional,
            kosmos.aodPromotedNotificationInteractor,
            kosmos.notificationsKeyguardViewStateRepository,
            kosmos.applicationContext,
        )

    private lateinit var testIcons: List<ActivePipelineEntryModel>

    @Before
    fun setup() {
        testScope.apply {
            activeNotificationListRepository.activeNotifications.value =
                kosmos.getPopulatedActiveNotificationsStore()
            testIcons =
                activeNotificationListRepository.activeNotifications.value.getPipelineModels()
        }
    }

    @Test
    fun filteredEntrySet() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.filteredNotifSet())
            assertThat(filteredSet)
                .comparingElementsUsing(byAssociatedNotifModel)
                .containsExactlyElementsIn(testIcons)
        }

    @Test
    fun filteredEntrySet_noExpandedBubbles() =
        testScope.runTest {
            whenever(kosmos.bubbles.isBubbleExpanded(eq("notif1"))).thenReturn(true)
            val filteredSet by collectLastValue(underTest.filteredNotifSet())
            assertThat(filteredSet).comparingElementsUsing(byIconNotifKey).doesNotContain("notif1")
        }

    @Test
    fun filteredEntrySet_noAmbient() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.filteredNotifSet(showAmbient = false))
            assertThat(filteredSet).comparingElementsUsing(byIconIsAmbient).doesNotContain(true)
            assertThat(filteredSet)
                .comparingElementsUsing(byAssociatedNotifModel)
                .containsNoneIn(
                    testIcons.filter {
                        (it is ActiveNotificationModel && it.isSuppressedFromStatusBar) ||
                            (it is ActiveBundleModel && it.key == "bundle2")
                    }
                )
        }

    @Test
    fun filteredEntrySet_noLowPriority() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.filteredNotifSet(showLowPriority = false))
            assertThat(filteredSet)
                .comparingElementsUsing(byAssociatedNotifModel)
                .containsNoneIn(testIcons.filter { it is ActiveNotificationModel && it.isSilent })
        }

    @Test
    fun filteredEntrySet_noDismissed() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.filteredNotifSet(showDismissed = false))
            assertThat(filteredSet)
                .comparingElementsUsing(byAssociatedNotifModel)
                .containsNoneIn(
                    testIcons.filter { it is ActiveNotificationModel && it.isRowDismissed }
                )
        }

    @Test
    fun filteredEntrySet_noRepliedMessages() =
        testScope.runTest {
            val filteredSet by
                collectLastValue(underTest.filteredNotifSet(showRepliedMessages = false))
            assertThat(filteredSet)
                .comparingElementsUsing(byAssociatedNotifModel)
                .containsNoneIn(
                    testIcons.filter { it is ActiveNotificationModel && it.isLastMessageFromReply }
                )
        }

    @Test
    fun filteredEntrySet_noPulsing_notifsNotFullyHidden() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.filteredNotifSet(showPulsing = false))
            notificationsKeyguardInteractor.setNotificationsFullyHidden(false)
            assertThat(filteredSet)
                .comparingElementsUsing(byAssociatedNotifModel)
                .containsNoneIn(testIcons.filter { it is ActiveNotificationModel && it.isPulsing })
        }

    @Test
    fun filteredEntrySet_noPulsing_notifsFullyHidden() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.filteredNotifSet(showPulsing = false))
            notificationsKeyguardInteractor.setNotificationsFullyHidden(true)
            assertThat(filteredSet)
                .comparingElementsUsing(byAssociatedNotifModel)
                .containsAnyIn(testIcons.filter { it is ActiveNotificationModel && it.isPulsing })
        }

    @Test
    fun filteredEntrySet_showAodPromoted() {
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.filteredNotifSet(showAodPromoted = true))
            assertThat(filteredSet)
                .comparingElementsUsing(byAssociatedNotifModel)
                .containsAnyIn(
                    testIcons.filter { it is ActiveNotificationModel && it.promotedContent != null }
                )
        }
    }

    @Test
    fun filteredEntrySet_noAodPromoted() {
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.filteredNotifSet(showAodPromoted = false))
            assertThat(filteredSet)
                .comparingElementsUsing(byAssociatedNotifModel)
                .containsNoneIn(
                    testIcons.filter { it is ActiveNotificationModel && it.promotedContent != null }
                )
        }
    }
}

@SmallTest
@RunWith(AndroidJUnit4::class)
class AlwaysOnDisplayNotificationIconsInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope
        get() = kosmos.testScope

    private val underTest
        get() = kosmos.alwaysOnDisplayNotificationIconsInteractor

    private val activeNotificationListRepository
        get() = kosmos.activeNotificationListRepository

    private lateinit var testIcons: List<ActivePipelineEntryModel>

    @Before
    fun setup() {
        testScope.apply {
            activeNotificationListRepository.activeNotifications.value =
                kosmos.getPopulatedActiveNotificationsStore()
            testIcons =
                activeNotificationListRepository.activeNotifications.value.getPipelineModels()
        }
    }

    @Test
    fun filteredEntrySet_noExpandedBubbles() =
        testScope.runTest {
            whenever(kosmos.bubbles.isBubbleExpanded(eq("notif1"))).thenReturn(true)
            val filteredSet by collectLastValue(underTest.aodNotifs)
            assertThat(filteredSet).comparingElementsUsing(byIconNotifKey).doesNotContain("notif1")
        }

    @Test
    fun filteredEntrySet_noAmbient() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.aodNotifs)
            assertThat(filteredSet).comparingElementsUsing(byIconIsAmbient).doesNotContain(true)
            assertThat(filteredSet)
                .comparingElementsUsing(byAssociatedNotifModel)
                .containsNoneIn(
                    testIcons.filter {
                        (it is ActiveNotificationModel && it.isSuppressedFromStatusBar) ||
                            (it is ActiveBundleModel && it.key == "bundle2")
                    }
                )
        }

    @Test
    fun filteredEntrySet_noDismissed() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.aodNotifs)
            assertThat(filteredSet)
                .comparingElementsUsing(byAssociatedNotifModel)
                .containsNoneIn(
                    testIcons.filter { it is ActiveNotificationModel && it.isRowDismissed }
                )
        }

    @Test
    fun filteredEntrySet_noRepliedMessages() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.aodNotifs)
            assertThat(filteredSet)
                .comparingElementsUsing(byAssociatedNotifModel)
                .containsNoneIn(
                    testIcons.filter { it is ActiveNotificationModel && it.isLastMessageFromReply }
                )
        }

    @Test
    fun filteredEntrySet_showPulsing_notifsNotFullyHidden_bypassDisabled() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.aodNotifs)
            kosmos.fakeDeviceEntryBypassRepository.setBypassEnabled(false)
            kosmos.notificationsKeyguardInteractor.setNotificationsFullyHidden(false)
            assertThat(filteredSet)
                .comparingElementsUsing(byAssociatedNotifModel)
                .containsAnyIn(testIcons.filter { it is ActiveNotificationModel && it.isPulsing })
        }

    @Test
    fun filteredEntrySet_showPulsing_notifsFullyHidden_bypassDisabled() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.aodNotifs)
            kosmos.fakeDeviceEntryBypassRepository.setBypassEnabled(false)
            kosmos.notificationsKeyguardInteractor.setNotificationsFullyHidden(true)
            assertThat(filteredSet)
                .comparingElementsUsing(byAssociatedNotifModel)
                .containsAnyIn(testIcons.filter { it is ActiveNotificationModel && it.isPulsing })
        }

    @Test
    fun filteredEntrySet_noPulsing_notifsNotFullyHidden_bypassEnabled() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.aodNotifs)
            kosmos.fakeDeviceEntryBypassRepository.setBypassEnabled(true)
            kosmos.notificationsKeyguardInteractor.setNotificationsFullyHidden(false)
            assertThat(filteredSet)
                .comparingElementsUsing(byAssociatedNotifModel)
                .containsNoneIn(testIcons.filter { it is ActiveNotificationModel && it.isPulsing })
        }

    @Test
    fun filteredEntrySet_showPulsing_notifsFullyHidden_bypassEnabled() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.aodNotifs)
            kosmos.fakeDeviceEntryBypassRepository.setBypassEnabled(true)
            kosmos.notificationsKeyguardInteractor.setNotificationsFullyHidden(true)
            assertThat(filteredSet)
                .comparingElementsUsing(byAssociatedNotifModel)
                .containsAnyIn(testIcons.filter { it is ActiveNotificationModel && it.isPulsing })
        }
}

@SmallTest
@RunWith(AndroidJUnit4::class)
class StatusBarNotificationIconsInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val underTest =
        StatusBarNotificationIconsInteractor(
            kosmos.testDispatcher,
            kosmos.notificationIconsInteractor,
            kosmos.notificationListenerSettingsRepository,
            kosmos.secureSettingsRepository,
        )

    private val activeNotificationListRepository
        get() = kosmos.activeNotificationListRepository

    private lateinit var testIcons: List<ActivePipelineEntryModel>

    @Before
    fun setup() {
        testScope.apply {
            activeNotificationListRepository.activeNotifications.value =
                kosmos.getPopulatedActiveNotificationsStore()
            testIcons =
                activeNotificationListRepository.activeNotifications.value.getPipelineModels()
        }
    }

    @Test
    fun filteredEntrySet_noExpandedBubbles() =
        testScope.runTest {
            whenever(kosmos.bubbles.isBubbleExpanded(eq("notif1"))).thenReturn(true)
            val filteredSet by collectLastValue(underTest.statusBarNotifs)
            assertThat(filteredSet).comparingElementsUsing(byIconNotifKey).doesNotContain("notif1")
        }

    @Test
    fun filteredEntrySet_noAmbient() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.statusBarNotifs)
            assertThat(filteredSet).comparingElementsUsing(byIconIsAmbient).doesNotContain(true)
            assertThat(filteredSet)
                .comparingElementsUsing(byAssociatedNotifModel)
                .containsNoneIn(
                    testIcons.filter {
                        (it is ActiveNotificationModel && it.isSuppressedFromStatusBar) ||
                            (it is ActiveBundleModel && it.key == "bundle2")
                    }
                )
        }

    @Test
    fun importanceFilter_defaultOff_toggleAndRankingUpdate() =
        testScope.runTest {
            val minimum =
                activeNotificationModel("minimum", groupKey = "minimum", importance = IMPORTANCE_MIN)
            val low = activeNotificationModel("low", groupKey = "low", importance = IMPORTANCE_LOW)
            val normal =
                activeNotificationModel("normal", groupKey = "normal", importance = IMPORTANCE_DEFAULT)
            fun publish(minimumModel: ActiveNotificationModel) {
                activeNotificationListRepository.activeNotifications.value =
                    ActiveNotificationsStore.Builder().apply {
                        addIndividualNotif(minimumModel)
                        addIndividualNotif(low)
                        addIndividualNotif(normal)
                    }.build()
            }
            publish(minimum)
            val icons by collectLastValue(underTest.statusBarNotifs)
            assertThat(icons)
                .comparingElementsUsing(byIconNotifKey)
                .containsExactly("minimum", "low", "normal")

            kosmos.secureSettingsRepository.setBoolean(
                "mosaic_status_bar_hide_low_priority_icons", true
            )
            assertThat(icons).comparingElementsUsing(byIconNotifKey).containsExactly("low", "normal")
            publish(minimum.copy(importance = IMPORTANCE_LOW))
            assertThat(icons)
                .comparingElementsUsing(byIconNotifKey)
                .containsExactly("minimum", "low", "normal")
            publish(minimum)
            assertThat(icons).comparingElementsUsing(byIconNotifKey).containsExactly("low", "normal")

            kosmos.secureSettingsRepository.setBoolean(
                "mosaic_status_bar_hide_low_priority_icons", false
            )
            assertThat(icons)
                .comparingElementsUsing(byIconNotifKey)
                .containsExactly("minimum", "low", "normal")
        }

    @Test
    fun importanceFilter_keepsOtherSurfacesAndSilentPolicy() =
        testScope.runTest {
            val minimum =
                activeNotificationModel("minimum", groupKey = "minimum", importance = IMPORTANCE_MIN)
            val silent =
                activeNotificationModel(
                    "silent",
                    groupKey = "silent",
                    importance = IMPORTANCE_LOW,
                    isSilent = true,
                )
            activeNotificationListRepository.activeNotifications.value =
                ActiveNotificationsStore.Builder().apply {
                    addIndividualNotif(minimum)
                    addIndividualNotif(silent)
                }.build()
            kosmos.notificationListenerSettingsRepository.showSilentStatusIcons.value = true
            val icons by collectLastValue(underTest.statusBarNotifs)
            val shelf by collectLastValue(kosmos.notificationIconsInteractor.filteredNotifSet())
            val aod by collectLastValue(kosmos.alwaysOnDisplayNotificationIconsInteractor.aodNotifs)
            kosmos.secureSettingsRepository.setBoolean(
                "mosaic_status_bar_hide_low_priority_icons", true
            )
            assertThat(icons).comparingElementsUsing(byIconNotifKey).containsExactly("silent")
            assertThat(shelf).comparingElementsUsing(byIconNotifKey).containsExactly("minimum", "silent")
            assertThat(aod).comparingElementsUsing(byIconNotifKey).containsExactly("minimum", "silent")

            kosmos.notificationListenerSettingsRepository.showSilentStatusIcons.value = false
            assertThat(icons).isEmpty()
            kosmos.secureSettingsRepository.setBoolean(
                "mosaic_status_bar_hide_low_priority_icons", false
            )
            assertThat(icons).comparingElementsUsing(byIconNotifKey).containsExactly("minimum")
        }

    @Test
    fun importanceFilter_groupsAndBundles() =
        testScope.runTest {
            val minimum =
                activeNotificationModel("minimum", groupKey = "minimum", importance = IMPORTANCE_MIN)
            val low = activeNotificationModel("low", groupKey = "low", importance = IMPORTANCE_LOW)
            val icon = Icon.createWithResource(context, android.R.drawable.ic_dialog_info)
            activeNotificationListRepository.activeNotifications.value =
                ActiveNotificationsStore.Builder().apply {
                    addNotifGroup(ActiveNotificationGroupModel("minGroup", minimum, listOf(low)))
                    addNotifGroup(ActiveNotificationGroupModel("lowGroup", low, listOf(minimum)))
                    addBundle(ActiveBundleModel("minBundle", icon, listOf(minimum)))
                    addBundle(ActiveBundleModel("mixedBundle", icon, listOf(minimum, low)))
                    addBundle(
                        ActiveBundleModel(
                            "groupBundle",
                            icon,
                            listOf(ActiveNotificationGroupModel("nested", minimum, listOf(low))),
                        )
                    )
                }.build()
            kosmos.notificationListenerSettingsRepository.showSilentStatusIcons.value = true
            val icons by collectLastValue(underTest.statusBarNotifs)
            kosmos.secureSettingsRepository.setBoolean(
                "mosaic_status_bar_hide_low_priority_icons", true
            )
            assertThat(icons)
                .comparingElementsUsing(byIconNotifKey)
                .containsExactly("low", "mixedBundle", "groupBundle")
        }

    @Test
    fun filteredEntrySet_noLowPriority_whenDontShowSilentIcons() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.statusBarNotifs)
            kosmos.notificationListenerSettingsRepository.showSilentStatusIcons.value = false
            assertThat(filteredSet)
                .comparingElementsUsing(byAssociatedNotifModel)
                .containsNoneIn(
                    testIcons.filter {
                        (it is ActiveNotificationModel && it.isSilent) || (it is ActiveBundleModel)
                    }
                )
        }

    @Test
    fun filteredEntrySet_showLowPriority_whenShowSilentIcons() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.statusBarNotifs)
            kosmos.notificationListenerSettingsRepository.showSilentStatusIcons.value = true
            assertThat(filteredSet)
                .comparingElementsUsing(byAssociatedNotifModel)
                .containsAnyIn(
                    testIcons.filter {
                        (it is ActiveNotificationModel && it.isSilent) || (it is ActiveBundleModel)
                    }
                )
        }

    @Test
    fun filteredEntrySet_noDismissed() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.statusBarNotifs)
            assertThat(filteredSet)
                .comparingElementsUsing(byAssociatedNotifModel)
                .containsNoneIn(
                    testIcons.filter { it is ActiveNotificationModel && it.isRowDismissed }
                )
        }

    @Test
    fun filteredEntrySet_noRepliedMessages() =
        testScope.runTest {
            val filteredSet by collectLastValue(underTest.statusBarNotifs)
            assertThat(filteredSet)
                .comparingElementsUsing(byAssociatedNotifModel)
                .containsNoneIn(
                    testIcons.filter { it is ActiveNotificationModel && it.isLastMessageFromReply }
                )
        }

    @Test
    fun hasStatusBarNotifications_isFalse_whenRepoIsEmpty() =
        testScope.runTest {
            // GIVEN the repo is empty
            activeNotificationListRepository.activeNotifications.value = ActiveNotificationsStore()

            // WHEN the flow is collected
            val value by collectLastValue(underTest.hasStatusBarNotifications)

            // THEN the value is false
            assertThat(value).isFalse()
        }

    @Test
    fun hasStatusBarNotifications_isTrue_whenRepoIsPopulated() =
        testScope.runTest {
            // GIVEN the repo is populated (from @Before)
            // AND silent icons are visible (to ensure a non-empty set)
            kosmos.notificationListenerSettingsRepository.showSilentStatusIcons.value = true

            // WHEN the flow is collected
            val value by collectLastValue(underTest.hasStatusBarNotifications)

            // THEN the value is true
            assertThat(value).isTrue()
        }

    @Test
    fun hasStatusBarNotifications_transitionsToFalse_whenRepoIsCleared() =
        testScope.runTest {
            // GIVEN the repo is populated and value is true
            kosmos.notificationListenerSettingsRepository.showSilentStatusIcons.value = true
            val value by collectLastValue(underTest.hasStatusBarNotifications)
            assertThat(value).isTrue()

            // WHEN the repo is cleared
            activeNotificationListRepository.activeNotifications.value = ActiveNotificationsStore()

            // THEN the value transitions to false
            assertThat(value).isFalse()
        }

    @Test
    fun hasStatusBarNotifications_transitionsToTrue_whenRepoIsPopulated() =
        testScope.runTest {
            // GIVEN the repo is empty and value is false
            activeNotificationListRepository.activeNotifications.value = ActiveNotificationsStore()
            val value by collectLastValue(underTest.hasStatusBarNotifications)

            assertThat(value).isFalse()

            // WHEN the repo is populated
            kosmos.notificationListenerSettingsRepository.showSilentStatusIcons.value = true
            activeNotificationListRepository.activeNotifications.value =
                kosmos.getPopulatedActiveNotificationsStore()

            // THEN the value transitions to true
            assertThat(value).isTrue()
        }

    @Test
    fun hasStatusBarNotifications_isFalse_whenOnlyAmbientNotifsArePresent() =
        testScope.runTest {
            // GIVEN the store *only* contains an ambient notification
            val ambientNotifKey = "ambient_notif_1"
            val ambientNotif = activeNotificationModel(key = ambientNotifKey, isAmbient = true)
            val ambientNotifMap = mapOf(ambientNotifKey to ambientNotif)

            val ambientOnlyStore = ActiveNotificationsStore(individuals = ambientNotifMap)
            assertThat(ambientOnlyStore.individuals).isNotEmpty()
            activeNotificationListRepository.activeNotifications.value = ambientOnlyStore

            // WHEN the flow is collected
            val value by collectLastValue(underTest.hasStatusBarNotifications)

            // THEN the value is false, as ambient notifs are filtered
            assertThat(value).isFalse()
        }

    @Test
    fun hasStatusBarNotifications_isFalse_whenOnlyBubbleNotifsArePresent() =
        testScope.runTest {
            // GIVEN the store *only* contains a notification that is an expanded bubble
            val bubbleNotifKey = "bubble_notif_1"
            val bubbleNotif = activeNotificationModel(key = bubbleNotifKey)
            val bubbleNotifMap = mapOf(bubbleNotifKey to bubbleNotif)

            // Mock that this specific key *is* an expanded bubble
            whenever(kosmos.bubbles.isBubbleExpanded(eq(bubbleNotifKey))).thenReturn(true)

            val bubbleOnlyStore = ActiveNotificationsStore(individuals = bubbleNotifMap)

            assertThat(bubbleOnlyStore.individuals).isNotEmpty()
            activeNotificationListRepository.activeNotifications.value = bubbleOnlyStore

            // WHEN the flow is collected
            val value by collectLastValue(underTest.hasStatusBarNotifications)

            // THEN the value is false, as expanded bubbles are filtered
            assertThat(value).isFalse()
        }
}
