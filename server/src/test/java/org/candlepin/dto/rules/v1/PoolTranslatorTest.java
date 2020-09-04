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
package org.candlepin.dto.rules.v1;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.candlepin.dto.AbstractTranslatorTest;
import org.candlepin.dto.ModelTranslator;
import org.candlepin.model.Pool;
import org.candlepin.model.Product;

import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;



/**
 * Test suite for the PoolTranslator (Rules) class.
 */
public class PoolTranslatorTest extends AbstractTranslatorTest<Pool, PoolDTO, PoolTranslator> {

    protected PoolTranslator translator = new PoolTranslator();

    @Override
    protected void initModelTranslator(ModelTranslator modelTranslator) {
        modelTranslator.registerTranslator(this.translator, Pool.class, PoolDTO.class);
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

        source.setQuantity(1L);
        source.setStartDate(new Date());
        source.setEndDate(new Date());

        Map<String, String> attributes = new HashMap<>();
        attributes.put(Pool.Attributes.SOURCE_POOL_ID, "true");
        source.setAttributes(attributes);

        source.setRestrictedToUsername("restricted-to-username-value");
        source.setConsumed(6L);

        source.setAttribute(Pool.Attributes.DEVELOPMENT_POOL, "true");

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

        return source;
    }

    @Override
    protected PoolDTO initDestinationObject() {
        return new PoolDTO();
    }

    @Override
    protected void verifyOutput(Pool source, PoolDTO dest, boolean childrenGenerated) {
        if (source != null) {
            assertEquals(source.getId(), dest.getId());
            assertEquals(source.getQuantity(), dest.getQuantity());
            assertEquals(source.getStartDate(), dest.getStartDate());
            assertEquals(source.getEndDate(), dest.getEndDate());
            assertEquals(source.getAttributes(), dest.getAttributes());
            assertEquals(source.getRestrictedToUsername(), dest.getRestrictedToUsername());
            assertEquals(source.getConsumed(), dest.getConsumed());

            if (childrenGenerated) {
                // If a product is present on the source pool, verify the data from it is present
                // on our DTO
                Product srcProduct = source.getProduct();
                if (srcProduct != null) {
                    assertEquals(srcProduct.getId(), dest.getProductId());
                    assertEquals(srcProduct.getAttributes(), dest.getProductAttributes());

                    // Check provided products
                    verifyProductsOutput(srcProduct.getProvidedProducts(), dest.getProvidedProducts());

                    // Check derived products
                    Product srcDerivedProduct = srcProduct.getDerivedProduct();
                    if (srcDerivedProduct != null) {
                        assertEquals(srcDerivedProduct.getId(), dest.getDerivedProductId());

                        verifyProductsOutput(srcDerivedProduct.getProvidedProducts(),
                            dest.getDerivedProvidedProducts());
                    }
                    else {
                        assertNull(dest.getDerivedProductId());
                        assertNull(dest.getDerivedProvidedProducts());
                    }
                }
                else {
                    assertNull(dest.getProductId());
                    assertNull(dest.getProductAttributes());
                    assertNull(dest.getProvidedProducts());

                    assertNull(dest.getDerivedProductId());
                    assertNull(dest.getDerivedProvidedProducts());
                }
            }
            else {
                assertNull(dest.getProvidedProducts());
                assertNull(dest.getDerivedProvidedProducts());
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
        Set<PoolDTO.ProvidedProductDTO> dtos) {

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
