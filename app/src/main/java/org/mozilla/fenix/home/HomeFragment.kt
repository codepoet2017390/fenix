/* This Source Code Form is subject to the terms of the Mozilla Public
   License, v. 2.0. If a copy of the MPL was not distributed with this
   file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.home

import android.content.res.Resources
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.navigation.Navigation
import kotlinx.android.synthetic.main.fragment_home.*
import kotlinx.android.synthetic.main.fragment_home.view.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import mozilla.components.browser.menu.BrowserMenu
import mozilla.components.browser.session.Session
import mozilla.components.browser.session.SessionManager
import org.mozilla.fenix.BrowserDirection
import org.mozilla.fenix.BrowsingModeManager
import org.mozilla.fenix.DefaultThemeManager
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.utils.ItsNotBrokenSnack
import org.mozilla.fenix.R
import org.mozilla.fenix.components.metrics.Event
import org.mozilla.fenix.ext.archive
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.home.sessions.ArchivedSession
import org.mozilla.fenix.home.sessions.SessionsAction
import org.mozilla.fenix.home.sessions.SessionsChange
import org.mozilla.fenix.home.sessions.SessionsComponent
import org.mozilla.fenix.home.tabs.TabsAction
import org.mozilla.fenix.home.tabs.TabsChange
import org.mozilla.fenix.home.tabs.TabsComponent
import org.mozilla.fenix.home.tabs.TabsState
import org.mozilla.fenix.home.tabs.toSessionViewState
import org.mozilla.fenix.mvi.ActionBusFactory
import org.mozilla.fenix.mvi.getAutoDisposeObservable
import org.mozilla.fenix.mvi.getManagedEmitter
import org.mozilla.fenix.settings.SupportUtils
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt

@SuppressWarnings("TooManyFunctions", "LargeClass")
class HomeFragment : Fragment(), CoroutineScope {
    private val bus = ActionBusFactory.get(this)
    private var sessionObserver: SessionManager.Observer? = null
    private var homeMenu: HomeMenu? = null
    private lateinit var tabsComponent: TabsComponent
    private lateinit var sessionsComponent: SessionsComponent

    private lateinit var job: Job
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        job = Job()
        val view = inflater.inflate(R.layout.fragment_home, container, false)
        val sessionManager = requireComponents.core.sessionManager
        tabsComponent = TabsComponent(
            view.homeContainer,
            bus,
            (activity as HomeActivity).browsingModeManager.isPrivate,
            TabsState(sessionManager.sessions.map { it.toSessionViewState(it == sessionManager.selectedSession) })
        )
        sessionsComponent = SessionsComponent(view.homeContainer, bus)

        ActionBusFactory.get(this).logMergedObservables()
        val activity = activity as HomeActivity
        DefaultThemeManager.applyStatusBarTheme(activity.window, activity.themeManager, activity)
        return view
    }

    @SuppressWarnings("LongMethod")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupHomeMenu()
        setupPrivateBrowsingDescription()
        updatePrivateSessionDescriptionVisibility()

        sessionsComponent.view.visibility = if ((activity as HomeActivity).browsingModeManager.isPrivate)
            View.GONE else View.VISIBLE
        tabsComponent.tabList.isNestedScrollingEnabled = false
        sessionsComponent.view.isNestedScrollingEnabled = false

        val bundles = requireComponents.core.sessionStorage.bundles(limit = temporaryNumberOfSessions)

        bundles.observe(this, Observer { sessionBundles ->
            val archivedSessions = sessionBundles
                .filter { it.id != requireComponents.core.sessionStorage.current()?.id }
                .mapNotNull { sessionBundle ->
                    sessionBundle.id?.let {
                        ArchivedSession(it, sessionBundle, sessionBundle.lastSavedAt, sessionBundle.urls)
                    }
                }

            getManagedEmitter<SessionsChange>().onNext(SessionsChange.Changed(archivedSessions))
        })

        val searchIcon = requireComponents.search.searchEngineManager.getDefaultSearchEngine(
            requireContext()
        ).let {
            BitmapDrawable(resources, it.icon)
        }

        view.menuButton.setOnClickListener {
            homeMenu?.menuBuilder?.build(requireContext())?.show(
                anchor = it,
                orientation = BrowserMenu.Orientation.DOWN)
        }

        val iconSize = resources.getDimension(R.dimen.preference_icon_drawable_size).toInt()
        searchIcon.setBounds(0, 0, iconSize, iconSize)
        view.toolbar.setCompoundDrawables(searchIcon, null, null, null)
        val roundToInt = (toolbarPaddingDp * Resources.getSystem().displayMetrics.density).roundToInt()
        view.toolbar.compoundDrawablePadding = roundToInt
        view.toolbar.setOnClickListener {
            val directions = HomeFragmentDirections.actionHomeFragmentToSearchFragment(null)
            Navigation.findNavController(it).navigate(directions)

            requireComponents.analytics.metrics.track(Event.SearchBarTapped(Event.SearchBarTapped.Source.HOME))
        }

        // There is currently an issue with visibility changes in ConstraintLayout 2.0.0-alpha3
        // https://issuetracker.google.com/issues/122090772
        // For now we're going to manually implement KeyTriggers.
        view.homeLayout.setTransitionListener(object : MotionLayout.TransitionListener {
            private val firstKeyTrigger = KeyTrigger(
                firstKeyTriggerFrame,
                { view.toolbar_wrapper.transitionToDark() },
                { view.toolbar_wrapper.transitionToLight() }
            )
            private val secondKeyTrigger = KeyTrigger(
                secondKeyTriggerFrame,
                { view.toolbar_wrapper.transitionToDarkNoBorder() },
                { view.toolbar_wrapper.transitionToDarkFromNoBorder() }
            )

            override fun onTransitionChange(
                motionLayout: MotionLayout?,
                startId: Int,
                endId: Int,
                progress: Float
            ) {
                firstKeyTrigger.conditionallyFire(progress)
                secondKeyTrigger.conditionallyFire(progress)
            }

            override fun onTransitionCompleted(p0: MotionLayout?, p1: Int) { }
        })

        val isPrivate = (activity as HomeActivity).browsingModeManager.isPrivate

        view.toolbar_wrapper.isPrivateModeEnabled = isPrivate
        privateBrowsingButton.contentDescription = contentDescriptionForPrivateBrowsingButton(isPrivate)

        privateBrowsingButton.setOnClickListener {
            val browsingModeManager = (activity as HomeActivity).browsingModeManager
            browsingModeManager.mode = when (browsingModeManager.mode) {
                BrowsingModeManager.Mode.Normal -> BrowsingModeManager.Mode.Private
                BrowsingModeManager.Mode.Private -> BrowsingModeManager.Mode.Normal
            }
        }
    }

    override fun onDestroyView() {
        homeMenu = null
        job.cancel()
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        (activity as AppCompatActivity).supportActionBar?.hide()
    }

    @SuppressWarnings("ComplexMethod")
    override fun onStart() {
        super.onStart()
        if (isAdded) {
            getAutoDisposeObservable<TabsAction>()
                .subscribe {
                    when (it) {
                        is TabsAction.Archive -> {
                            launch {
                                requireComponents.core.sessionStorage.archive(requireComponents.core.sessionManager)
                            }
                        }
                        is TabsAction.MenuTapped -> {
                            val isPrivate = (activity as HomeActivity).browsingModeManager.isPrivate
                            val titles = requireComponents.core.sessionManager.sessions
                                .filter { session -> session.private == isPrivate }
                                .map { session -> session.title }

                            val sessionType = if (isPrivate) {
                                SessionBottomSheetFragment.SessionType.Private(titles)
                            } else {
                                SessionBottomSheetFragment.SessionType.Current(titles)
                            }

                            openSessionMenu(sessionType)
                        }
                        is TabsAction.Select -> {
                            val session = requireComponents.core.sessionManager.findSessionById(it.sessionId)
                            requireComponents.core.sessionManager.select(session!!)
                            val directions = HomeFragmentDirections.actionHomeFragmentToBrowserFragment(it.sessionId)
                            Navigation.findNavController(view!!).navigate(directions)
                        }
                        is TabsAction.Close -> {
                            requireComponents.core.sessionManager.findSessionById(it.sessionId)?.let { session ->
                                requireComponents.core.sessionManager.remove(session)
                            }
                        }
                        is TabsAction.CloseAll -> {
                            requireComponents.useCases.tabsUseCases.removeAllTabsOfType.invoke(it.private)
                        }
                    }
                }

            getAutoDisposeObservable<SessionsAction>()
                .subscribe {
                    when (it) {
                        is SessionsAction.Select -> {
                            launch {
                                requireComponents.core.sessionStorage.archive(requireComponents.core.sessionManager)
                                it.archivedSession.bundle.restoreSnapshot()?.apply {
                                    requireComponents.core.sessionManager.restore(this)
                                    homeScrollView.smoothScrollTo(0, 0)
                                }
                            }
                        }
                        is SessionsAction.Delete -> {
                            launch(IO) {
                                requireComponents.core.sessionStorage.remove(it.archivedSession.bundle)
                            }
                        }
                        is SessionsAction.MenuTapped ->
                            openSessionMenu(SessionBottomSheetFragment.SessionType.Archived(it.archivedSession))
                        is SessionsAction.ShareTapped ->
                            ItsNotBrokenSnack(context!!).showSnackbar(issueNumber = "244")
                    }
                }
        }

        sessionObserver = subscribeToSessions()
        sessionObserver?.onSessionsRestored()
    }

    override fun onPause() {
        super.onPause()
        sessionObserver?.let {
            requireComponents.core.sessionManager.unregister(it)
        }
    }

    private fun setupHomeMenu() {
        homeMenu = HomeMenu(requireContext()) {
            val directions = when (it) {
                HomeMenu.Item.Settings -> HomeFragmentDirections.actionHomeFragmentToSettingsFragment()
                HomeMenu.Item.Library -> HomeFragmentDirections.actionHomeFragmentToLibraryFragment()
                HomeMenu.Item.Help -> return@HomeMenu // Not implemented yetN
            }

            Navigation.findNavController(homeLayout).navigate(directions)
        }
    }

    private fun contentDescriptionForPrivateBrowsingButton(isPrivate: Boolean): String {
        val resourceId =
            if (isPrivate) R.string.content_description_disable_private_browsing_button else
                R.string.content_description_private_browsing_button

        return getString(resourceId)
    }

    private fun setupPrivateBrowsingDescription() {
        // Format the description text to include a hyperlink
        val descriptionText = String
            .format(private_session_description.text.toString(), System.getProperty("line.separator"))
        val linkStartIndex = descriptionText.indexOf("\n\n") + 2
        val linkAction = object : ClickableSpan() {
            override fun onClick(widget: View?) {
                requireComponents.useCases.tabsUseCases.addPrivateTab
                    .invoke(SupportUtils.getSumoURLForTopic(context!!, SupportUtils.SumoTopic.PRIVATE_BROWSING_MYTHS))
                (activity as HomeActivity).openToBrowser(requireComponents.core.sessionManager.selectedSession?.id,
                    BrowserDirection.FromHome)
            }
        }
        val textWithLink = SpannableString(descriptionText).apply {
            setSpan(linkAction, linkStartIndex, descriptionText.length, 0)

            val colorSpan = ForegroundColorSpan(private_session_description.currentTextColor)
            setSpan(colorSpan, linkStartIndex, descriptionText.length, 0)
        }
        private_session_description.movementMethod = LinkMovementMethod.getInstance()
        private_session_description.text = textWithLink
    }

    private fun updatePrivateSessionDescriptionVisibility() {
        val isPrivate = (activity as HomeActivity).browsingModeManager.isPrivate
        val hasNoTabs = requireComponents.core.sessionManager.all.none { it.private }

        private_session_description_wrapper.visibility = if (isPrivate && hasNoTabs) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun subscribeToSessions(): SessionManager.Observer {
        val observer = object : SessionManager.Observer {
            override fun onSessionAdded(session: Session) {
                super.onSessionAdded(session)
                emitSessionChanges()
                updatePrivateSessionDescriptionVisibility()
            }

            override fun onSessionRemoved(session: Session) {
                super.onSessionRemoved(session)
                emitSessionChanges()
                updatePrivateSessionDescriptionVisibility()
            }

            override fun onSessionSelected(session: Session) {
                super.onSessionSelected(session)
                emitSessionChanges()
                updatePrivateSessionDescriptionVisibility()
            }

            override fun onSessionsRestored() {
                super.onSessionsRestored()
                emitSessionChanges()
                updatePrivateSessionDescriptionVisibility()
            }

            override fun onAllSessionsRemoved() {
                super.onAllSessionsRemoved()
                emitSessionChanges()
                updatePrivateSessionDescriptionVisibility()
            }
        }
        requireComponents.core.sessionManager.register(observer)
        return observer
    }

    private fun emitSessionChanges() {
        val sessionManager = requireComponents.core.sessionManager
        getManagedEmitter<TabsChange>().onNext(
            TabsChange.Changed(
                sessionManager.sessions
                    .filter { (activity as HomeActivity).browsingModeManager.isPrivate == it.private }
                    .map { it.toSessionViewState(it == sessionManager.selectedSession) }
            )
        )
    }

    private fun openSessionMenu(sessionType: SessionBottomSheetFragment.SessionType) {
        SessionBottomSheetFragment.create(sessionType).apply {
            onArchive = {
                launch {
                    requireComponents.core.sessionStorage.archive(requireComponents.core.sessionManager)
                }
            }
            onDelete = {
                when (it) {
                    is SessionBottomSheetFragment.SessionType.Archived -> {
                        launch(IO) {
                            requireComponents.core.sessionStorage.remove(it.archivedSession.bundle)
                        }
                    }
                    is SessionBottomSheetFragment.SessionType.Current -> {
                        requireComponents.useCases.tabsUseCases.removeAllTabsOfType.invoke(false)
                        launch(IO) {
                            requireComponents.core.sessionStorage.current()?.apply {
                                requireComponents.core.sessionStorage.remove(this)
                            }
                        }
                    }
                    is SessionBottomSheetFragment.SessionType.Private -> {
                        requireComponents.useCases.tabsUseCases.removeAllTabsOfType.invoke(true)
                    }
                }
            }
        }.show(requireActivity().supportFragmentManager, SessionBottomSheetFragment.overflowFragmentTag)
    }

    companion object {
        const val addTabButtonIncreaseDps = 8
        const val overflowButtonIncreaseDps = 8
        const val toolbarPaddingDp = 12f
        const val firstKeyTriggerFrame = 55
        const val secondKeyTriggerFrame = 90
        const val temporaryNumberOfSessions = 25
    }
}
