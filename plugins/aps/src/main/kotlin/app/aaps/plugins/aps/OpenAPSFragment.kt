
    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        menu.add(Menu.FIRST, ID_MENU_RUN, 0, rh.gs(R.string.openapsma_run)).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        MenuCompat.setGroupDividerEnabled(menu, true)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            ID_MENU_RUN -> {
                binding.lastrun.text = rh.gs(R.string.executing)
                lifecycleScope.launch { activePlugin.activeAPS.invoke("OpenAPS menu", false) }
                true
            }


            else        -> false
        }

    @Synchronized
    override fun onResume() {
        super.onResume()

        disposable += rxBus
            .toObservable(EventOpenAPSUpdateGui::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventResetOpenAPSGui::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ resetGUI(it.text) }, fabricPrivacy::logException)

        updateGUI()
    }

    @Synchronized
    override fun onPause() {
        super.onPause()
        disposable.clear()
    }

    @Synchronized
    override fun onDestroy() {
        super.onDestroy()
    }

    @Synchronized
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    @SuppressLint("SetTextI18n")
    @Synchronized
    private fun updateGUI() {
        if (_binding == null) return
        val openAPSPlugin = activePlugin.activeAPS
        openAPSPlugin.lastAPSResult?.let { lastAPSResult ->
            binding.result.text = lastAPSResult.rawData().dataClassToHtml()
            binding.request.text = lastAPSResult.resultAsSpanned()
            binding.glucosestatus.text = lastAPSResult.glucoseStatus?.dataClassToHtml(listOf("glucose", "delta", "shortAvgDelta", "longAvgDelta"))
            binding.currenttemp.text = lastAPSResult.currentTemp?.dataClassToHtml()
            binding.iobdata.text = rh.gs(R.string.array_of_elements, lastAPSResult.iobData?.size) + "\n" + lastAPSResult.iob?.dataClassToHtml()
            binding.profile.text = lastAPSResult.oapsProfile?.dataClassToHtml() ?: lastAPSResult.oapsProfileAutoIsf?.dataClassToHtml()
            binding.mealdata.text = lastAPSResult.mealData?.dataClassToHtml()
            binding.scriptdebugdata.text = lastAPSResult.scriptDebug?.joinToString("\n")
            binding.constraints.text = lastAPSResult.inputConstraints?.getReasons()
            binding.autosensdata.text = lastAPSResult.autosensResult?.dataClassToHtml()
            binding.lastrun.text = dateUtil.dateAndTimeString(openAPSPlugin.lastAPSRun)
        }
        binding.swipeRefresh.isRefreshing = false
    }

    @Synchronized
    private fun resetGUI(text: String) {
        if (_binding == null) return
        binding.result.text = text
        binding.glucosestatus.text = ""
        binding.currenttemp.text = ""
        binding.iobdata.text = ""
        binding.profile.text = ""
        binding.mealdata.text = ""
        binding.autosensdata.text = ""
        binding.scriptdebugdata.text = ""
        binding.request.text = ""
        binding.lastrun.text = ""
        binding.swipeRefresh.isRefreshing = false
    }

    private fun Any.dataClassToHtml(): Spanned =
        HtmlHelper.fromHtml(
            StringBuilder().also { sb ->
                this::class.declaredMemberProperties.forEach { property ->
                    property.call(this)?.let { value ->
                        if (ClassUtils.isPrimitiveOrWrapper(value::class.java)) sb.append(property.name.bold(), ": ", value, br)
                        if (value is StringBuilder) sb.append(property.name.bold(), ": ", value.toString(), br)
                    }
                }
            }.toString()
        )

    private fun Any.dataClassToHtml(properties: List<String>): Spanned =
        HtmlHelper.fromHtml(
            StringBuilder().also { sb ->
                properties.forEach { property ->
                    this::class.declaredMemberProperties
                        .firstOrNull { it.name == property }?.call(this)
                        ?.let { value ->
                            if (ClassUtils.isPrimitiveOrWrapper(value::class.java)) sb.append(property.bold(), ": ", value, br)
                            if (value is StringBuilder) sb.append(property.bold(), ": ", value.toString(), br)
                        }
                }
            }.toString()
        )

    private fun String.bold(): String = "<b>$this</b>"
    private val br = "<br>"
}