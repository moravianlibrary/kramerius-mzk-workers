package cz.mzk.kramerius.plugin;

import org.ceskaexpedice.processplatform.api.AbstractPluginSpi;

import java.util.Set;

public class ExternalRefsSPI extends AbstractPluginSpi {

    @Override
    public String getMainClass() {
        return ExternalRefsStarter.class.getName();
    }

    @Override
    public Set<String> getScheduledProfiles() {
        return Set.of();
    }
}
