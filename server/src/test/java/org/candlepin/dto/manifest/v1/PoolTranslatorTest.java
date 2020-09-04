/**
 * Copyright (c) 2009 - 2018 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.candlepin.dto.manifest.v1;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.candlepin.dto.AbstractTranslatorTest;
import org.candlepin.dto.ModelTranslator;
import org.candlepin.model.Branding;
import org.candlepin.model.CertificateSerial;
import org.candlepin.model.Consumer;
import org.candlepin.model.Entitlement;
import org.candlepin.model.Pool;
import org.candlepin.model.Product;
import org.candlepin.model.SourceStack;
import org.candlepin.model.SourceSubscription;
import org.candlepin.model.SubscriptionsCertificate;

import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;



/**
 * Test suite for the PoolTranslator (manifest import/export) class.
 */
public class PoolTranslatorTest extends AbstractTranslatorTest<Pool, PoolDTO, PoolTranslator> {

    protected PoolTranslator translator = new PoolTranslator();

    // Using EntitlementTranslator instead of EntitlementTranslatorTest to avoid StackOverflow issues
    // caused by bidirectional reference between Pool and Entitlement.
    private EntitlementTranslator entitlementTranslator = new EntitlementTranslator();
    private ProductTranslatorTest productTranslatorTest = new ProductTranslatorTest();
    private OwnerTranslatorTest ownerTranslatorTest = new OwnerTranslatorTest();
    private BrandingTranslatorTest productBrandingTranslatorTest = new BrandingTranslatorTest();
    private CertificateTranslatorTest certificateTranslatorTest = new CertificateTranslatorTest();

    @Override
    protected void initModelTranslator(ModelTranslator modelTranslator) {
        this.productTranslatorTest.initModelTranslator(modelTranslator);
        this.ownerTranslatorTest.initModelTranslator(modelTranslator);
        this.productBrandingTranslatorTest.initModelTranslator(modelTranslator);
        this.certificateTranslatorTest.initModelTranslator(modelTranslator);

        modelTranslator.registerTranslator(this.translator, Pool.class, PoolDTO.class);
        modelTranslator.registerTranslator(this.entitlementTranslator,
            Entitlement.class, EntitlementDTO.class);
    }

    @Override
    protected PoolTranslator initObjectTranslator() {
        return this.translator;
    }

    private Product generateProduct(String prefix, int id) {
        return new Product()
            .setId(prefix + "-id-" + id)
            .setName(prefix + "-name-" + id)
            .setAttribute(prefix + "-attrib" + id + "-key", prefix + "-attrb" + id + "-value")
            .setAttribute(prefix + "-attrib" + id + "-key", prefix + "-attrb" + id + "-value")
            .setAttribute(prefix + "-attrib" + id + "-key", prefix + "-attrb" + id + "-value");
    }

    @Override
    protected Pool initSourceObject() {
        Pool source = new Pool();
        source.setId("pool-id");

        source.setOwner(this.ownerTranslatorTest.initSourceObject());

        Product mktProduct = this.generateProduct("mkt_product", 1);
        Product engProduct1 = this.generateProduct("eng_product", 1);
        Product engProduct2 = this.generateProduct("eng_product", 2);
        Product engProduct3 = this.generateProduct("eng_product", 3);

        Product derProduct = this.generateProduct("derived_product", 1);
        Product derEngProduct1 = this.generateProduct("derived_eng_prod", 1);
        Product derEngProduct2 = this.generateProduct("derived_eng_prod", 2);
        Product derEngProduct3 = this.generateProduct("derived_eng_prod", 3);

        mktProduct.setDerivedProduct(derProduct);
        mktProduct.setProvidedProducts(Arrays.asList(engProduct1, engProduct2, engProduct3));
        derProduct.setProvidedProducts(Arrays.asList(derEngProduct1, derEngProduct2, derEngProduct3));

        source.setProduct(mktProduct);

        Entitlement entitlement = new Entitlement();
        entitlement.setId("ent-id");
        source.setSourceEntitlement(entitlement);

        SubscriptionsCertificate subCert = new SubscriptionsCertificate();
        subCert.setId("cert-id");
        subCert.setKey("cert-key");
        subCert.setCert("cert-cert");
        subCert.setSerial(new CertificateSerial());
        source.setCertificate(subCert);

        SourceSubscription sourceSubscription = new SourceSubscription();
        sourceSubscription.setId("source-sub-id-1");
        sourceSubscription.setSubscriptionId("source-sub-subscription-id-1");
        sourceSubscription.setSubscriptionSubKey("source-sub-subscription-sub-key-1");
        source.setSourceSubscription(sourceSubscription);

        source.setActiveSubscription(true);

        source.setQuantity(1L);
        source.setStartDate(new Date());
        source.setEndDate(new Date());

        Map<String, String> attributes = new HashMap<>();
        attributes.put(Pool.Attributes.SOURCE_POOL_ID, "true");
        source.setAttributes(attributes);

        source.setRestrictedToUsername("restricted-to-username-value");
        source.setContractNumber("333");
        source.setAccountNumber("444");
        source.setOrderNumber("555");
        source.setConsumed(6L);
        source.setExported(7L);

        Map<String, String> calculatedAttributes = new HashMap<>();
        calculatedAttributes.put("calc-attribute-key-3", "calc-attribute-value-3");
        calculatedAttributes.put("calc-attribute-key-4", "calc-attribute-value-4");
        source.setCalculatedAttributes(calculatedAttributes);

        source.setUpstreamPoolId("upstream-pool-id-2");
        source.setUpstreamEntitlementId("upstream-entitlement-id-2");
        source.setUpstreamConsumerId("upstream-consumer-id-2");

        source.setAttribute(Pool.Attributes.DEVELOPMENT_POOL, "true");

        Consumer sourceConsumer = new Consumer();
        sourceConsumer.setUuid("source-consumer-uuid");

        SourceStack sourceStack = new SourceStack();
        sourceStack.setSourceStackId("source-stack-source-stack-id-1");
        sourceStack.setId("source-stack-id-1");
        sourceStack.setSourceConsumer(sourceConsumer);
        source.setSourceStack(sourceStack);

        return source;
    }

    @Override
    protected PoolDTO initDestinationObject() {
        return new PoolDTO();
    }

    @Override
    @SuppressWarnings("MethodLength")
    protected void verifyOutput(Pool source, PoolDTO dest, boolean childrenGenerated) {
        if (source != null) {
            assertEquals(source.getId(), dest.getId());
            assertEquals(source.getType().toString(), dest.getType());
            assertEquals(source.getActiveSubscription(), dest.isActiveSubscription());
            assertEquals(source.getQuantity(), dest.getQuantity());
            assertEquals(source.getStartDate(), dest.getStartDate());
            assertEquals(source.getEndDate(), dest.getEndDate());
            assertEquals(source.getAttributes(), dest.getAttributes());
            assertEquals(source.getRestrictedToUsername(), dest.getRestrictedToUsername());
            assertEquals(source.getContractNumber(), dest.getContractNumber());
            assertEquals(source.getAccountNumber(), dest.getAccountNumber());
            assertEquals(source.getOrderNumber(), dest.getOrderNumber());
            assertEquals(source.getConsumed(), dest.getConsumed());
            assertEquals(source.getExported(), dest.getExported());
            assertEquals(source.getCalculatedAttributes(), dest.getCalculatedAttributes());
            assertEquals(source.getUpstreamPoolId(), dest.getUpstreamPoolId());
            assertEquals(source.getUpstreamEntitlementId(), dest.getUpstreamEntitlementId());
            assertEquals(source.getUpstreamConsumerId(), dest.getUpstreamConsumerId());
            assertEquals(source.getStackId(), dest.getStackId());
            assertEquals(source.isStacked(), dest.isStacked());
            assertEquals(source.isDevelopmentPool(), dest.isDevelopmentPool());
            assertEquals(source.getSourceStackId(), dest.getSourceStackId());
            assertEquals(source.getSubscriptionSubKey(), dest.getSubscriptionSubKey());
            assertEquals(source.getSubscriptionId(), dest.getSubscriptionId());

            if (childrenGenerated) {
                this.ownerTranslatorTest
                    .verifyOutput(source.getOwner(), dest.getOwner(), true);

                this.certificateTranslatorTest
                    .verifyOutput(source.getCertificate(), dest.getCertificate(), true);

                Entitlement sourceSourceEntitlement = source.getSourceEntitlement();
                EntitlementDTO destSourceEntitlement = dest.getSourceEntitlement();
                if (sourceSourceEntitlement != null) {
                    assertEquals(sourceSourceEntitlement.getId(), destSourceEntitlement.getId());
                }
                else {
                    assertNull(destSourceEntitlement);
                }

                // If a product is present on the source pool, verify the data from it is present
                // on our DTO
                Product srcProduct = source.getProduct();
                if (srcProduct != null) {
                    assertEquals(srcProduct.getId(), dest.getProductId());
                    assertEquals(srcProduct.getName(), dest.getProductName());
                    assertEquals(srcProduct.getAttributes(), dest.getProductAttributes());

                    // Check branding
                    Collection<Branding> srcBranding = srcProduct.getBranding();
                    Collection<BrandingDTO> destBranding = dest.getBranding();
                    int matched = 0;

                    if (srcBranding != null) {
                        assertNotNull(destBranding);
                        assertEquals(srcBranding.size(), destBranding.size());

                        for (Branding branding : srcBranding) {
                            for (BrandingDTO dto : destBranding) {
                                assertNotNull(dto);
                                assertNotNull(dto.getProductId());

                                if (dto.getProductId().equals(branding.getProductId())) {
                                    ++matched;
                                    this.productBrandingTranslatorTest.verifyOutput(branding, dto, true);
                                }
                            }
                        }

                        assertEquals(destBranding.size(), matched);
                    }
                    else {
                        assertNull(destBranding);
                    }

                    // Check provided products
                    verifyProductsOutput(srcProduct.getProvidedProducts(), dest.getProvidedProducts());

                    // Check derived products
                    Product srcDerivedProduct = srcProduct.getDerivedProduct();
                    if (srcDerivedProduct != null) {
                        assertEquals(srcDerivedProduct.getId(), dest.getDerivedProductId());
                        assertEquals(srcDerivedProduct.getName(), dest.getDerivedProductName());
                        assertEquals(srcDerivedProduct.getAttributes(), dest.getDerivedProductAttributes());

                        verifyProductsOutput(srcDerivedProduct.getProvidedProducts(),
                            dest.getDerivedProvidedProducts());
                    }
                    else {
                        assertNull(dest.getDerivedProductId());
                        assertNull(dest.getDerivedProductName());
                        assertNull(dest.getDerivedProductAttributes());
                        assertNull(dest.getDerivedProvidedProducts());
                    }
                }
                else {
                    assertNull(dest.getBranding());
                    assertNull(dest.getProductId());
                    assertNull(dest.getProductName());
                    assertNull(dest.getProductAttributes());
                    assertNull(dest.getProvidedProducts());
                    assertNull(dest.getDerivedProductId());
                    assertNull(dest.getDerivedProductName());
                    assertNull(dest.getDerivedProductAttributes());
                    assertNull(dest.getDerivedProvidedProducts());
                }
            }
            else {
                assertNull(dest.getOwner());
                assertNull(dest.getSourceEntitlement());
                assertNull(dest.getBranding());
                assertNull(dest.getProvidedProducts());
                assertNull(dest.getDerivedProvidedProducts());
                assertNull(dest.getCertificate());
            }
        }
        else {
            assertNull(dest);
        }
    }

    /**
     * Verifies that the pool's sets of products are translated properly.
     *
     * @param originalProducts the original set or products we check against.
     *
     * @param dtoProducts the translated DTO set of products that we need to verify.
     */
    private static void verifyProductsOutput(Collection<Product> source,
        Collection<PoolDTO.ProvidedProductDTO> dtos) {

        if (source != null) {
            assertNotNull(dtos);
            assertEquals(source.size(), dtos.size());

            int matched = 0;

            for (Product product : source) {
                for (PoolDTO.ProvidedProductDTO dto : dtos) {
                    assertNotNull(dto);
                    assertNotNull(dto.getProductId());

                    if (dto.getProductId().equals(product.getId())) {
                        assertTrue(dto.getProductName().equals(product.getName()));
                        ++matched;
                    }
                }
            }

            assertEquals(dtos.size(), matched);
        }
        else {
            assertNull(dtos);
        }
    }
}
