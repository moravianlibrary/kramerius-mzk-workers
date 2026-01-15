package cz.mzk.kramerius;

import javax.xml.namespace.NamespaceContext;
import java.util.Iterator;

public class FoxmlNamespaceContext implements NamespaceContext {

    @Override
    public String getNamespaceURI(String prefix) {
        if ("foxml".equals(prefix)) return "info:fedora/fedora-system:def/foxml#";
        return null;
    }

    @Override
    public String getPrefix(String namespaceURI) {
        return null;
    }

    @Override
    public Iterator<String> getPrefixes(String namespaceURI) {
        return null;
    }
}
