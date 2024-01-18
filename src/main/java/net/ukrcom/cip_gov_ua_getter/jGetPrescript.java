/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package net.ukrcom.cip_gov_ua_getter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.IDN;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 *
 * @author olden
 */
public class jGetPrescript {

    protected final String urlPrescript;
    protected final URI uriCGUPrescript;
    protected final URL urlCGUPrescript;
    protected final URLConnection conCGUPrescript;
    protected String bodyPrescript;
    protected String id;

    public jGetPrescript(Properties p, String i) throws MalformedURLException, IOException {
        this.id = i;
        this.urlPrescript = p.getProperty(
                "urlPrescript",
                "https://cip.gov.ua/services/cm/api/attachment/download"
        ).trim().concat(this.id);
        this.uriCGUPrescript = URI.create(this.urlPrescript);
        this.urlCGUPrescript = uriCGUPrescript.toURL();
        this.conCGUPrescript = urlCGUPrescript.openConnection();
        try (Stream<String> lines = new BufferedReader(
                new InputStreamReader(this.conCGUPrescript.getInputStream()))
                .lines()) {
            this.bodyPrescript = lines.collect(Collectors.joining("\n"));
        }

    }

    public String[] getBodyPrescript() {
        StringBuilder sb = new StringBuilder();
        for (String s : this.bodyPrescript.split("\n")) {
            String domain = IDN.toASCII(
                    s.replaceAll("\s+", "")
            ).toLowerCase().trim();

            URI uri = URI.create(domain);
            if (uri.getHost() != null) {
                domain = uri.getHost();
            }

            domain = domain.replaceAll("^www\\.", "");
            sb.append(domain).append("\n");
        }
        return sb.toString().split("\n");
    }
}
