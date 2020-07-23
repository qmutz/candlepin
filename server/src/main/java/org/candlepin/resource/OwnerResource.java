/**
 * Copyright (c) 2009 - 2012 Red Hat, Inc.
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
package org.candlepin.resource;

import org.candlepin.async.JobConfig;
import org.candlepin.async.JobException;
import org.candlepin.async.JobManager;
import org.candlepin.async.tasks.HealEntireOrgJob;
import org.candlepin.async.tasks.ImportJob;
import org.candlepin.async.tasks.RefreshPoolsForProductJob;
import org.candlepin.async.tasks.RefreshPoolsJob;
import org.candlepin.async.tasks.UndoImportsJob;
import org.candlepin.audit.Event;
import org.candlepin.audit.Event.Target;
import org.candlepin.audit.Event.Type;
import org.candlepin.audit.EventAdapter;
import org.candlepin.audit.EventFactory;
import org.candlepin.audit.EventSink;
import org.candlepin.auth.Access;
import org.candlepin.auth.Principal;
import org.candlepin.auth.SubResource;
import org.candlepin.auth.Verify;
import org.candlepin.common.config.Configuration;
import org.candlepin.common.exceptions.BadRequestException;
import org.candlepin.common.exceptions.CandlepinException;
import org.candlepin.common.exceptions.ConflictException;
import org.candlepin.common.exceptions.ForbiddenException;
import org.candlepin.common.exceptions.IseException;
import org.candlepin.common.exceptions.NotFoundException;
import org.candlepin.common.exceptions.ResourceMovedException;
import org.candlepin.common.paging.Page;
import org.candlepin.common.paging.PageRequest;
import org.candlepin.config.ConfigProperties;
import org.candlepin.controller.ContentAccessManager;
import org.candlepin.controller.ContentAccessManager.ContentAccessMode;
import org.candlepin.controller.ContentManager;
import org.candlepin.controller.ManifestManager;
import org.candlepin.controller.OwnerManager;
import org.candlepin.controller.PoolManager;
import org.candlepin.controller.ProductManager;
import org.candlepin.dto.ModelTranslator;
import org.candlepin.dto.api.v1.ActivationKeyDTO;
import org.candlepin.dto.api.v1.ActivationKeyPoolDTO;
import org.candlepin.dto.api.v1.ActivationKeyProductDTO;
import org.candlepin.dto.api.v1.AsyncJobStatusDTO;
import org.candlepin.dto.api.v1.ConsumerDTOArrayElement;
import org.candlepin.dto.api.v1.ContentDTO;
import org.candlepin.dto.api.v1.ContentOverrideDTO;
import org.candlepin.dto.api.v1.EntitlementDTO;
import org.candlepin.dto.api.v1.EnvironmentDTO;
import org.candlepin.dto.api.v1.ImportRecordDTO;
import org.candlepin.dto.api.v1.KeyValueParamDTO;
import org.candlepin.dto.api.v1.NestedOwnerDTO;
import org.candlepin.dto.api.v1.OwnerDTO;
import org.candlepin.dto.api.v1.PoolDTO;
import org.candlepin.dto.api.v1.ProductCertificateDTO;
import org.candlepin.dto.api.v1.ProductContentDTO;
import org.candlepin.dto.api.v1.ProductDTO;
import org.candlepin.dto.api.v1.ProvidedProductDTO;
import org.candlepin.dto.api.v1.SystemPurposeAttributesDTO;
import org.candlepin.dto.api.v1.UeberCertificateDTO;
import org.candlepin.dto.api.v1.UpstreamConsumerDTO;
import org.candlepin.model.AsyncJobStatus;
import org.candlepin.model.CandlepinQuery;
import org.candlepin.model.Consumer;
import org.candlepin.model.ConsumerCurator;
import org.candlepin.model.ConsumerType;
import org.candlepin.model.Content;
import org.candlepin.model.Entitlement;
import org.candlepin.model.EntitlementCurator;
import org.candlepin.model.EntitlementFilterBuilder;
import org.candlepin.model.Environment;
import org.candlepin.model.EnvironmentCurator;
import org.candlepin.model.ExporterMetadata;
import org.candlepin.model.ExporterMetadataCurator;
import org.candlepin.model.ImportRecord;
import org.candlepin.model.ImportRecordCurator;
import org.candlepin.model.Owner;
import org.candlepin.model.OwnerContentCurator;
import org.candlepin.model.OwnerCurator;
import org.candlepin.model.OwnerInfo;
import org.candlepin.model.OwnerInfoCurator;
import org.candlepin.model.OwnerProduct;
import org.candlepin.model.OwnerProductCurator;
import org.candlepin.model.Pool;
import org.candlepin.model.Pool.PoolType;
import org.candlepin.model.PoolFilterBuilder;
import org.candlepin.model.Product;
import org.candlepin.model.ProductCertificate;
import org.candlepin.model.ProductCertificateCurator;
import org.candlepin.model.ProductContent;
import org.candlepin.model.ProductCurator;
import org.candlepin.model.Release;
import org.candlepin.model.SourceSubscription;
import org.candlepin.model.SystemPurposeAttributeType;
import org.candlepin.model.UeberCertificate;
import org.candlepin.model.UeberCertificateCurator;
import org.candlepin.model.UeberCertificateGenerator;
import org.candlepin.model.UpstreamConsumer;
import org.candlepin.model.activationkeys.ActivationKey;
import org.candlepin.model.activationkeys.ActivationKeyContentOverride;
import org.candlepin.model.activationkeys.ActivationKeyCurator;
import org.candlepin.model.dto.Subscription;
import org.candlepin.resource.util.CalculatedAttributesUtil;
import org.candlepin.resource.util.ConsumerTypeValidator;
import org.candlepin.resource.util.EntitlementFinderUtil;
import org.candlepin.resource.util.ResolverUtil;
import org.candlepin.resource.validation.DTOValidator;
import org.candlepin.resteasy.DateFormat;
import org.candlepin.service.OwnerServiceAdapter;
import org.candlepin.service.UniqueIdGenerator;
import org.candlepin.sync.ConflictOverrides;
import org.candlepin.sync.ImporterException;
import org.candlepin.sync.SyncDataFormatException;
import org.candlepin.sync.file.ManifestFileServiceException;
import org.candlepin.util.ContentOverrideValidator;
import org.candlepin.util.ServiceLevelValidator;
import org.candlepin.util.Util;

import ch.qos.logback.classic.Level;

import com.google.inject.Inject;
import com.google.inject.persist.Transactional;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.Authorization;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.hibernate.exception.ConstraintViolationException;
import org.jboss.resteasy.annotations.providers.jaxb.Wrapped;
import org.jboss.resteasy.core.ResteasyContext;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnap.commons.i18n.I18n;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.persistence.PersistenceException;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;

/**
 * Owner Resource
 */
@Path("/owners")
@Api(value = "owners", authorizations = { @Authorization("basic") })
public class OwnerResource implements OwnersApi {

    private static Logger log = LoggerFactory.getLogger(OwnerResource.class);

    private static final Pattern AK_CHAR_FILTER = Pattern.compile("^[a-zA-Z0-9_-]+$");

    private OwnerCurator ownerCurator;
    private OwnerInfoCurator ownerInfoCurator;
    private ActivationKeyCurator activationKeyCurator;
    private OwnerServiceAdapter ownerService;
    private ConsumerCurator consumerCurator;
    private I18n i18n;
    private EventSink sink;
    private EventFactory eventFactory;
    private EventAdapter eventAdapter;
    private ManifestManager manifestManager;
    private ExporterMetadataCurator exportCurator;
    private ImportRecordCurator importRecordCurator;
    private ContentAccessManager contentAccessManager;
    private PoolManager poolManager;
    private OwnerManager ownerManager;
    private EntitlementCurator entitlementCurator;
    private UeberCertificateCurator ueberCertCurator;
    private UeberCertificateGenerator ueberCertGenerator;
    private EnvironmentCurator envCurator;
    private CalculatedAttributesUtil calculatedAttributesUtil;
    private ContentOverrideValidator contentOverrideValidator;
    private ServiceLevelValidator serviceLevelValidator;
    private Configuration config;
    private ResolverUtil resolverUtil;
    private ConsumerTypeValidator consumerTypeValidator;
    private OwnerProductCurator ownerProductCurator;
    private ModelTranslator translator;
    private JobManager jobManager;
    private DTOValidator validator;
    private OwnerContentCurator ownerContentCurator;
    private UniqueIdGenerator idGenerator;
    private ContentManager contentManager;
    private ProductManager productManager;
    private ProductCertificateCurator productCertCurator;
    private ProductCurator productCurator;

    @Inject
    @SuppressWarnings("checkstyle:parameternumber")
    public OwnerResource(OwnerCurator ownerCurator,
        ActivationKeyCurator activationKeyCurator,
        ConsumerCurator consumerCurator,
        I18n i18n,
        EventSink sink,
        EventFactory eventFactory,
        EventAdapter eventAdapter,
        ContentAccessManager contentAccessManager,
        ManifestManager manifestManager,
        PoolManager poolManager,
        OwnerManager ownerManager,
        ExporterMetadataCurator exportCurator,
        OwnerInfoCurator ownerInfoCurator,
        ImportRecordCurator importRecordCurator,
        EntitlementCurator entitlementCurator,
        UeberCertificateCurator ueberCertCurator,
        UeberCertificateGenerator ueberCertGenerator,
        EnvironmentCurator envCurator,
        CalculatedAttributesUtil calculatedAttributesUtil,
        ContentOverrideValidator contentOverrideValidator,
        ServiceLevelValidator serviceLevelValidator,
        OwnerServiceAdapter ownerService,
        Configuration config,
        ResolverUtil resolverUtil,
        ConsumerTypeValidator consumerTypeValidator,
        OwnerProductCurator ownerProductCurator,
        ModelTranslator translator,
        JobManager jobManager,
        DTOValidator validator,
        OwnerContentCurator ownerContentCurator,
        UniqueIdGenerator idGenerator,
        ContentManager contentManager,
        ProductManager productManager,
        ProductCertificateCurator productCertCurator,
        ProductCurator productCurator) {

        this.ownerCurator = ownerCurator;
        this.ownerInfoCurator = ownerInfoCurator;
        this.activationKeyCurator = activationKeyCurator;
        this.consumerCurator = consumerCurator;
        this.i18n = i18n;
        this.sink = sink;
        this.eventFactory = eventFactory;
        this.exportCurator = exportCurator;
        this.importRecordCurator = importRecordCurator;
        this.contentAccessManager = contentAccessManager;
        this.poolManager = poolManager;
        this.manifestManager = manifestManager;
        this.ownerManager = ownerManager;
        this.eventAdapter = eventAdapter;
        this.entitlementCurator = entitlementCurator;
        this.ueberCertCurator = ueberCertCurator;
        this.ueberCertGenerator = ueberCertGenerator;
        this.envCurator = envCurator;
        this.calculatedAttributesUtil = calculatedAttributesUtil;
        this.contentOverrideValidator = contentOverrideValidator;
        this.serviceLevelValidator = serviceLevelValidator;
        this.ownerService = ownerService;
        this.config = config;
        this.resolverUtil = resolverUtil;
        this.consumerTypeValidator = consumerTypeValidator;
        this.ownerProductCurator = ownerProductCurator;
        this.translator = translator;
        this.jobManager = jobManager;
        this.validator = validator;
        this.ownerContentCurator = ownerContentCurator;
        this.idGenerator = idGenerator;
        this.contentManager = contentManager;
        this.productManager = productManager;
        this.productCertCurator = productCertCurator;
        this.productCurator = productCurator;
    }

    /**
     * Returns the owner object that is identified by the given key, if it is found in the system.
     * Otherwise, it throws a NotFoundException.
     *
     * @param key the key that is associated with the owner we are searching for.
     *
     * @return the owner that was found in the system based on the given key.
     *
     * @throws NotFoundException
     *  if the owner with the given key was not found in the system.
     *
     * @throws BadRequestException
     *  if the given Owner key is null or empty.
     */
    private Owner findOwnerByKey(String key) {
        Owner owner;
        if (key != null && !key.isEmpty()) {
            owner = ownerCurator.getByKey(key);
        }
        else {
            throw new BadRequestException(i18n.tr("Owner key is null or empty."));
        }

        if (owner == null) {
            throw new NotFoundException(i18n.tr("Owner with key \"{0}\" was not found", key));
        }

        return owner;
    }

    /**
     * Returns the owner object that is identified by the given id, if it is found in the system.
     * Otherwise, it throws a NotFoundException.
     *
     * @param id the id of the owner we are searching for.
     *
     * @return the owner that was found in the system based on the given id.
     *
     * @throws NotFoundException
     *  if the owner with the given id was not found in the system.
     *
     * @throws BadRequestException
     *  if the given Owner id is null or empty.
     */
    private Owner findOwnerById(String id) {
        Owner owner = null;
        if (id != null && !id.isEmpty()) {
            owner = ownerCurator.get(id);
        }
        else {
            throw new BadRequestException(i18n.tr("Owner id is null or empty."));
        }

        if (owner == null) {
            throw new NotFoundException(i18n.tr("Owner with id \"{0}\" was not found", id));
        }

        return owner;
    }

    /**
     * Returns the owner object that is identified by the given id or key, if it is found in the system.
     * A search with the id has priority. If the id is null, only then a search with the key is performed.
     * If both the id and the key are null, a {@link BadRequestException} is thrown.
     *
     * @param id the id of the owner we are searching for.
     *
     * @param key the key that is associated with the owner we are searching for.
     *
     * @return the owner that was found in the system based on the given id or key.
     *
     * @throws BadRequestException
     *  If both the id and the key are null or empty.
     */
    private Owner findOwnerByIdOrKey(String id, String key) {
        if (id != null && !id.isEmpty()) {
            return this.findOwnerById(id);
        }
        else if (key != null && !key.isEmpty()) {
            return this.findOwnerByKey(key);
        }
        else {
            throw new BadRequestException(i18n.tr(
                "Owner lookup failed because id and key were null or empty."));
        }
    }

    /**
     * Returns the entitlement object that is identified by the given id, if it is found in the system.
     * Otherwise, it throws a NotFoundException.
     *
     * @param entitlementId the id of the entitlement we are searching for.
     *
     * @return the entitlement that was found in the system based on the given id.
     *
     * @throws NotFoundException
     *  if the entitlement with the given id was not found in the system.
     *
     * @throws BadRequestException
     *  if the given Entitlement id is null or empty.
     */
    private Entitlement findEntitlement(String entitlementId) {
        Entitlement entitlement = null;
        if (entitlementId != null && !entitlementId.isEmpty()) {
            entitlement = entitlementCurator.get(entitlementId);
        }
        else {
            throw new BadRequestException(i18n.tr("Entitlement id is null or empty."));
        }

        if (entitlement == null) {
            throw new NotFoundException(
                    i18n.tr("Entitlement with id {0} could not be found.", entitlementId));
        }

        return entitlement;
    }

    /**
     * Returns the consumer object that is identified by the given uuid, if it is found in the system.
     * Otherwise, it throws a NotFoundException.
     *
     * @param consumerUuid the uuid of the consumer we are searching for.
     *
     * @return the consumer that was found in the system based on the given uuid.
     *
     * @throws NotFoundException
     *  if the consumer with the given uuid was not found in the system.
     *
     * @throws BadRequestException
     *  if the given Consumer id is null or empty.
     */
    private Consumer findConsumer(String consumerUuid) {
        Consumer consumer;
        if (consumerUuid != null && !consumerUuid.isEmpty()) {
            consumer = consumerCurator.findByUuid(consumerUuid);
        }
        else {
            throw new BadRequestException(i18n.tr("Consumer id is null or empty."));
        }

        if (consumer == null) {
            throw new NotFoundException(i18n.tr("No such unit: {0}", consumerUuid));
        }

        return consumer;
    }

    /**
     * Returns the pool object that is identified by the given id, if it is found in the system.
     * Otherwise, it throws a NotFoundException.
     *
     * @param poolId the id of the pool we are searching for.
     *
     * @return the pool that was found in the system based on the given id.
     *
     * @throws NotFoundException
     *  if the pool with the given id was not found in the system.
     *
     * @throws BadRequestException
     *  if the given Pool id is null or empty.
     */
    private Pool findPool(String poolId) {
        Pool pool;
        if (poolId != null && !poolId.isEmpty()) {
            pool = poolManager.get(poolId);
        }
        else {
            throw new BadRequestException(i18n.tr("Pool id is null or empty."));
        }

        if (pool == null) {
            throw new BadRequestException(i18n.tr("Pool with id {0} could not be found.", poolId));
        }

        return pool;
    }

    /**
     * Returns the product object that is identified by the given product id and owner object,
     * if it is found in the system. Otherwise, it throws a NotFoundException.
     *
     * @param owner
     *  The owner of the product we are searching for
     *
     * @param productId
     *  The ID of the product to lookup
     *
     * @throws IllegalArgumentException
     *  if owner is null, or if productId is null or empty
     *
     * @throws BadRequestException
     *  if a product with the specified ID cannot be found within the context of the given owner
     *
     * @return
     *  the product with the specified product ID and owner
     */
    private Product findProduct(Owner owner, String productId) {
        if (owner == null) {
            throw new IllegalArgumentException("owner is null");
        }

        if (productId == null || productId.isEmpty()) {
            throw new IllegalArgumentException("productId is null or empty");
        }

        Product product = this.ownerProductCurator.getProductById(owner, productId);

        if (product == null) {
            throw new BadRequestException(
                i18n.tr("Unable to find a product with the ID \"{0}\" for owner \"{1}\"",
                    productId, owner.getKey()));
        }

        return product;
    }

    /**
     * Populates the specified entity with data from the provided DTO. This method will not set the
     * ID, key, upstream consumer, content access mode list or content access mode fields.
     *
     * @param entity
     *  The entity instance to populate
     *
     * @param dto
     *  The DTO containing the data with which to populate the entity
     *
     * @throws IllegalArgumentException
     *  if either entity or dto are null
     */
    protected void populateEntity(Owner entity, OwnerDTO dto) {
        if (entity == null) {
            throw new IllegalArgumentException("the owner model entity is null");
        }

        if (dto == null) {
            throw new IllegalArgumentException("the owner dto is null");
        }

        if (dto.getDisplayName() != null) {
            entity.setDisplayName(dto.getDisplayName());
        }

        if (dto.getParentOwner() != null) {
            // Impl note:
            // We do not allow modifying a parent owner through its children, so all we'll do here
            // is set the parent owner and ignore everything else; including further nested owners.

            NestedOwnerDTO pdto = dto.getParentOwner();
            Owner parent = null;

            if (pdto.getId() != null) {
                // look up by ID
                parent = this.ownerCurator.get(pdto.getId());
            }
            else if (pdto.getKey() != null) {
                // look up by key
                parent = this.ownerCurator.getByKey(pdto.getKey());
            }

            if (parent == null) {
                throw new NotFoundException(i18n.tr("Unable to find parent owner: {0}", pdto));
            }

            entity.setParentOwner(parent);
        }

        if (dto.getContentPrefix() != null) {
            entity.setContentPrefix(dto.getContentPrefix());
        }

        if (dto.getDefaultServiceLevel() != null) {
            if (dto.getDefaultServiceLevel().isEmpty()) {
                entity.setDefaultServiceLevel(null);
            }
            else {
                this.serviceLevelValidator.validate(entity.getId(), dto.getDefaultServiceLevel());
                entity.setDefaultServiceLevel(dto.getDefaultServiceLevel());
            }
        }

        if (dto.getLogLevel() != null) {
            entity.setLogLevel(dto.getLogLevel());
        }

        if (dto.getAutobindDisabled() != null) {
            entity.setAutobindDisabled(dto.getAutobindDisabled());
        }

        if (dto.getAutobindHypervisorDisabled() != null) {
            entity.setAutobindHypervisorDisabled(dto.getAutobindHypervisorDisabled());
        }

        // Note: Do not set the content access mode list or content access mode here. Those should
        // be done through ContentAccessManager.updateOwnerContentAccess instead.
    }

    /**
     * Populates the specified entity with data from the provided DTO. This method will not set the
     * ID field.
     *
     * @param entity
     *  The entity instance to populate
     *
     * @param dto
     *  The DTO containing the data with which to populate the entity
     *
     * @throws IllegalArgumentException
     *  if either entity or dto are null
     */
    protected void populateEntity(ActivationKey entity, ActivationKeyDTO dto) {
        if (entity == null) {
            throw new IllegalArgumentException("the activation key model entity is null");
        }

        if (dto == null) {
            throw new IllegalArgumentException("the activation key dto is null");
        }

        if (dto.getName() != null) {
            entity.setName(dto.getName());
        }

        if (dto.getDescription() != null) {
            entity.setDescription(dto.getDescription());
        }

        if (dto.getAutoAttach() != null) {
            entity.setAutoAttach(dto.getAutoAttach());
        }

        if (dto.getServiceLevel() != null) {
            entity.setServiceLevel(dto.getServiceLevel());
        }

        if (dto.getOwner() != null) {
            entity.setOwner(lookupOwnerFromDto(dto.getOwner()));
        }

        if (dto.getServiceLevel() != null) {
            if (dto.getServiceLevel().isEmpty()) {
                entity.setServiceLevel(null);
            }
            else {
                entity.setServiceLevel(dto.getServiceLevel());
            }
        }

        if (dto.getContentOverrides() != null) {
            if (dto.getContentOverrides().isEmpty()) {
                entity.setContentOverrides(new HashSet<>());
            }
            else {
                for (ContentOverrideDTO overrideDTO : dto.getContentOverrides()) {
                    if (overrideDTO != null) {
                        entity.addContentOverride(
                            new ActivationKeyContentOverride(entity, overrideDTO.getContentLabel(),
                            overrideDTO.getName(), overrideDTO.getValue()));
                    }
                }
            }
        }

        if (dto.getReleaseVer() != null) {
            entity.setReleaseVer(new Release(dto.getReleaseVer().getReleaseVer()));
        }

        if (dto.getUsage() != null) {
            entity.setUsage(dto.getUsage());
        }

        if (dto.getRole() != null) {
            entity.setRole(dto.getRole());
        }

        if (dto.getAddOns() != null) {
            entity.setAddOns(new HashSet<>(dto.getAddOns()));
        }

        if (dto.getPools() != null) {
            if (dto.getPools().isEmpty()) {
                entity.setPools(new HashSet<>());
            }
            else {
                for (ActivationKeyPoolDTO poolDTO : dto.getPools()) {
                    if (poolDTO != null) {
                        Pool pool = findPool(poolDTO.getPoolId());
                        entity.addPool(pool, poolDTO.getQuantity());
                    }
                }
            }
        }

        if (dto.getProducts() != null) {
            if (dto.getProducts().isEmpty()) {
                entity.setProducts(new HashSet<>());
            }
            else {
                Set<String> productIds = dto.getProducts().stream()
                    .map(ActivationKeyProductDTO::getProductId)
                    .collect(Collectors.toSet());

                for (String productId : productIds) {
                    if (productId != null) {
                        Product product = findProduct(entity.getOwner(), productId);
                        entity.addProduct(product);
                    }
                }
            }
        }
    }

    /*
     * Populates the specified entity with data from the provided DTO.
     * TODO: Remove this method once EnvironmentDTO gets moved to spec-first
     *  and starts using NestedOwnerDTO.
     */

    /*
     * Populates the specified entity with data from the provided DTO.
     */
    private Owner lookupOwnerFromDto(NestedOwnerDTO ownerDto) {
        return this.findOwnerByIdOrKey(ownerDto.getId(), ownerDto.getKey());
    }

    /**
     * Populates the specified entity with data from the provided DTO.
     *
     * @param entity
     *  The entity instance to populate
     *
     * @param dto
     *  The DTO containing the data with which to populate the entity
     *
     * @throws IllegalArgumentException
     *  if either entity or dto are null, or if the dto's environment content is not empty
     */
    protected void populateEntity(Environment entity, EnvironmentDTO dto) {

        if (entity == null) {
            throw new IllegalArgumentException("the environment model entity is null");
        }

        if (dto == null) {
            throw new IllegalArgumentException("the environment dto is null");
        }

        if (CollectionUtils.isNotEmpty(dto.getEnvironmentContent())) {
            throw new IllegalArgumentException("can not specify environment content at creation time");
        }

        entity.setId(dto.getId());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setOwner(lookupOwnerFromDto(dto.getOwner()));
    }

    /**
     * Populates an entity that is to be created with data from the provided DTO.
     *
     * @param entity
     *  The entity instance to populate
     *
     * @param dto
     *  The DTO containing the data with which to populate the entity
     *
     * @throws IllegalArgumentException
     *  if either entity or dto are null
     */
    @SuppressWarnings("checkstyle:methodlength")
    protected void populateEntity(Pool entity, PoolDTO dto) {
        if (entity == null) {
            throw new IllegalArgumentException("entity is null");
        }

        if (dto == null) {
            throw new IllegalArgumentException("dto is null");
        }

        if (dto.getId() != null) {
            entity.setId(dto.getId());
        }

        if (dto.getStartDate() != null) {
            entity.setStartDate(Util.toDate(dto.getStartDate()));
        }

        if (dto.getEndDate() != null) {
            entity.setEndDate(Util.toDate(dto.getEndDate()));
        }

        if (dto.getQuantity() != null) {
            entity.setQuantity(dto.getQuantity());
        }

        if (dto.getAttributes() != null) {
            if (dto.getAttributes().isEmpty()) {
                entity.setAttributes(Collections.emptyMap());
            }
            else {
                entity.setAttributes(Util.toMap(dto.getAttributes()));
            }
        }

        if (dto.getProvidedProducts() != null) {
            if (dto.getProvidedProducts().isEmpty()) {
                entity.setProvidedProducts(Collections.emptySet());
            }
            else {
                Set<Product> products = new HashSet<>();
                for (ProvidedProductDTO providedProductDTO : dto.getProvidedProducts()) {
                    if (providedProductDTO != null) {
                        Product newProd = findProduct(entity.getOwner(), providedProductDTO.getProductId());
                        products.add(newProd);
                    }
                }
                entity.setProvidedProducts(products);
            }
        }

        if (dto.getDerivedProvidedProducts() != null) {
            if (dto.getDerivedProvidedProducts().isEmpty()) {
                entity.setDerivedProvidedProducts(Collections.emptySet());
            }
            else {
                Set<Product> derivedProducts = new HashSet<>();
                for (ProvidedProductDTO derivedProvidedProductDTO :
                    dto.getDerivedProvidedProducts()) {
                    if (derivedProvidedProductDTO != null) {
                        Product newDerivedProd =
                            findProduct(entity.getOwner(), derivedProvidedProductDTO.getProductId());
                        derivedProducts.add(newDerivedProd);
                    }
                }
                entity.setDerivedProvidedProducts(derivedProducts);
            }
        }
    }

    /**
     * Retrieves a list of Owners
     *
     * @return a list of Owner objects
     * @httpcode 200
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Wrapped(element = "owners")
    @ApiOperation(notes = "Retrieves a list of Owners", value = "List Owners", response = OwnerDTO.class,
        responseContainer = "list")
    public CandlepinQuery<OwnerDTO> list(@QueryParam("key") String keyFilter) {
        CandlepinQuery<Owner> query = keyFilter != null ?
            this.ownerCurator.getByKeys(Arrays.asList(keyFilter)) :
            this.ownerCurator.listAll();

        return this.translator.translateQuery(query, OwnerDTO.class);
    }

    /**
     * Retrieves a single Owner
     * <p>
     * <pre>
     * {
     *   "parentOwner" : null,
     *   "id" : "database_id",
     *   "key" : "admin",
     *   "displayName" : "Admin Owner",
     *   "contentPrefix" : null,
     *   "defaultServiceLevel" : null,
     *   "upstreamConsumer" : null,
     *   "logLevel" : null,
     *   "href" : "/owners/admin",
     *   "created" : [date],
     *   "updated" : [date]
     * }
     * </pre>
     *
     * @param ownerKey Owner ID.
     * @return an Owner object
     * @httpcode 404
     * @httpcode 200
     */
    @GET
    @Path("/{owner_key}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(notes = "Retrieves a single Owner", value = "Get Owner", response = OwnerDTO.class)
    @ApiResponses({ @ApiResponse(code = 404, message = "An owner not found") })
    public OwnerDTO getOwner(@PathParam("owner_key") @Verify(Owner.class) String ownerKey) {
        Owner owner = findOwnerByKey(ownerKey);
        return this.translator.translate(owner, OwnerDTO.class);
    }

    /**
     * Retrieves the Owner Info for an Owner
     *
     * @param ownerKey Owner ID.
     * @return an OwnerInfo object
     * @httpcode 404
     * @httpcode 200
     */
    @GET
    @Path("/{owner_key}/info")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(notes = "Retrieves the Owner Info for an Owner", value = "Get Owner Info")
    @ApiResponses({ @ApiResponse(code = 404, message = "An owner not found") })
    public OwnerInfo getOwnerInfo(@PathParam("owner_key")
        @Verify(value = Owner.class, subResource = SubResource.CONSUMERS) String ownerKey) {
        Owner owner = findOwnerByKey(ownerKey);
        return ownerInfoCurator.getByOwner(owner);
    }

    /**
     * Creates an Owner
     *
     * @return an Owner object
     * @httpcode 400
     * @httpcode 200
     */
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(notes = "Creates an Owner", value = "Create Owner", response = OwnerDTO.class)
    @ApiResponses({ @ApiResponse(code = 400, message = "Invalid owner specified in body") })
    public OwnerDTO createOwner(@ApiParam(name = "owner", required = true) OwnerDTO dto) {
        // Verify that we have an owner key (as required)
        if (StringUtils.isBlank(dto.getKey())) {
            throw new BadRequestException(i18n.tr("Owners must be created with a valid key"));
        }

        Owner owner = new Owner();
        owner.setKey(dto.getKey());

        // Check that the default service level is *not* set at this point
        if (!StringUtils.isBlank(dto.getDefaultServiceLevel())) {
            throw new BadRequestException(
                i18n.tr("The default service level cannot be specified during owner creation"));
        }

        // Validate and set content access mode list & content access mode
        boolean configureContentAccess = false;

        final String defaultContentAccess = ContentAccessMode.getDefault().toDatabaseValue();
        String contentAccessModeList = dto.getContentAccessModeList();
        String contentAccessMode = dto.getContentAccessMode();

        if (!StringUtils.isBlank(contentAccessMode) && !contentAccessMode.equals(defaultContentAccess)) {
            if (config.getBoolean(ConfigProperties.STANDALONE)) {
                throw new BadRequestException(
                    i18n.tr("The owner content access mode and content access mode list cannot be set " +
                        "directly in standalone mode."));
            }

            configureContentAccess = true;
        }
        else {
            contentAccessMode = defaultContentAccess;
        }

        if (!StringUtils.isBlank(contentAccessModeList) &&
            !contentAccessModeList.equals(defaultContentAccess)) {

            // Impl note: We have to allow this for the time being due to pre-existing, expected
            // behaviors. This shouldn't impact actual functionality since the mode can't be set,
            // but we still need to allow setting the mode list.

            configureContentAccess = true;
        }
        else {
            contentAccessModeList = defaultContentAccess;
        }

        this.validateContentAccessModeChanges(owner, contentAccessModeList, contentAccessMode);

        // Translate the DTO to an entity Owner.
        this.populateEntity(owner, dto);
        owner.setContentAccessModeList(contentAccessModeList);
        owner.setContentAccessMode(contentAccessMode);

        // Try to persist the owner
        try {
            owner = this.ownerCurator.create(owner);

            if (owner == null) {
                throw new BadRequestException(i18n.tr("Could not create the Owner: {0}", owner));
            }

            if (configureContentAccess) {
                // Apply content access configuration if the user has given us non-default
                // content access settings
                owner = this.contentAccessManager
                    .updateOwnerContentAccess(owner, contentAccessModeList, contentAccessMode);
            }
        }
        catch (Exception e) {
            log.debug("Unable to create owner: ", e);
            throw new BadRequestException(i18n.tr("Could not create the Owner: {0}", owner));
        }

        log.info("Created owner: {}", owner);
        sink.emitOwnerCreated(owner);

        return this.translator.translate(owner, OwnerDTO.class);
    }

    /**
     * Updates an Owner
     * <p>
     * To un-set the defaultServiceLevel for an owner, submit an empty string.
     *
     * @param key
     * @param dto
     * @return an Owner object
     * @httpcode 404
     * @httpcode 200
     */
    @PUT
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("{owner_key}")
    @Transactional
    @ApiOperation(notes = "To un-set the defaultServiceLevel for an owner, submit an empty string.",
        value = "Update Owner", response = OwnerDTO.class)
    @ApiResponses({ @ApiResponse(code = 404, message = "Owner not found") })
    public OwnerDTO updateOwner(@PathParam("owner_key") @Verify(Owner.class) String key,
        @ApiParam(name = "owner", required = true) OwnerDTO dto) {
        log.debug("Updating owner: {}", key);

        Owner owner = findOwnerByKey(key);

        // Reject changes to the content access mode in standalone mode
        boolean updateContentAccess = false;
        String contentAccessModeList = dto.getContentAccessModeList();
        String contentAccessMode = dto.getContentAccessMode();

        // Check content access mode bits
        boolean caListChanged = contentAccessModeList != null &&
            !contentAccessModeList.equals(owner.getContentAccessModeList());

        boolean caModeChanged = contentAccessMode != null &&
            !contentAccessMode.equals(owner.getContentAccessMode());

        if (caListChanged || caModeChanged) {
            if (config.getBoolean(ConfigProperties.STANDALONE)) {
                throw new BadRequestException(
                    i18n.tr("The owner content access mode and content access mode list cannot be set " +
                        "directly in standalone mode."));
            }

            // This kinda doubles up on some work here, but at least we nice, clear error messages
            // rather than spooky ISEs.
            this.validateContentAccessModeChanges(owner, contentAccessModeList, contentAccessMode);
            updateContentAccess = true;
        }

        // Do the bulk of our entity population
        this.populateEntity(owner, dto);

        // Refresh content access mode if necessary
        if (updateContentAccess) {
            owner = this.contentAccessManager
                .updateOwnerContentAccess(owner, contentAccessModeList, contentAccessMode);
        }

        owner = ownerCurator.merge(owner);
        ownerCurator.flush();

        // Build and queue the owner modified event
        Event event = this.eventFactory.getEventBuilder(Target.OWNER, Type.MODIFIED)
            .setEventData(owner)
            .buildEvent();

        this.sink.queueEvent(event);

        // Output the updated owner
        return this.translator.translate(owner, OwnerDTO.class);
    }

    /**
     * Validates that the given content access mode changes can be used by the specified owner.
     *
     * @param owner
     *  the owner for which to check if the content access mode list is valid
     *
     * @param calist
     *  the content access mode list to check
     *
     * @param camode
     *  the content access mode
     *
     * @throws BadRequestException
     *  if the content access mode list is not currently valid for the given owner
     */
    private void validateContentAccessModeChanges(Owner owner, String calist, String camode) {
        if (calist != null) {
            if (calist.isEmpty()) {
                calist = ContentAccessMode.getDefault().toDatabaseValue();
            }

            String[] modes = calist.split(",");
            for (String mode : modes) {
                try {
                    ContentAccessMode.resolveModeName(mode);
                }
                catch (IllegalArgumentException e) {
                    throw new BadRequestException(this.i18n.tr("Content access mode list contains " +
                        "an unsupported mode: {0}", mode));
                }
            }
        }
        else {
            calist = owner.getContentAccessModeList();
        }

        if (camode != null) {
            if (camode.isEmpty()) {
                camode = ContentAccessMode.getDefault().toDatabaseValue();
            }

            if (calist == null || calist.isEmpty()) {
                calist = ContentAccessMode.getDefault().toDatabaseValue();
            }

            String[] modes = calist.split(",");
            if (!ArrayUtils.contains(modes, camode)) {
                throw new BadRequestException(this.i18n.tr("Content access mode is not present " +
                    "in the content access mode list for this org: {0}", camode));
            }
        }
    }

    /**
     * Removes an Owner
     *
     * @httpcode 404
     * @httpcode 200
     */
    @DELETE
    @Path("/{owner_key}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(notes = "Removes an Owner", value = "Delete Owner")
    @ApiResponses({ @ApiResponse(code = 404, message = "Owner not found") })
    public void deleteOwner(@PathParam("owner_key") String ownerKey,
        @QueryParam("revoke") @DefaultValue("true") boolean revoke,
        @QueryParam("force") @DefaultValue("false") boolean force) {

        Owner owner = findOwnerByKey(ownerKey);
        Event event = eventFactory.ownerDeleted(owner);

        try {
            ownerManager.cleanupAndDelete(owner, revoke);
        }
        catch (PersistenceException e) {
            if (e.getCause() instanceof ConstraintViolationException) {
                throw new ConflictException(e.getMessage(), e);
            }
            else {
                throw e;
            }
        }

        sink.queueEvent(event);
    }

    /**
     * Retrieves the list of Entitlements for an Owner
     *
     * @param ownerKey id of the owner whose entitlements are sought.
     * @return a list of Entitlement objects
     * @httpcode 404
     * @httpcode 200
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{owner_key}/entitlements")
    @ApiOperation(notes = "Retrieves the list of Entitlements for an Owner. This" +
        " endpoint supports paging with query parameters. For more details please visit " +
        "https://www.candlepinproject.org/docs/candlepin/pagination.html#paginating-results-from-candlepin",
        value = "List Owner Entitlements")
    @ApiResponses({ @ApiResponse(code = 404, message = "Owner not found") })
    public List<EntitlementDTO> ownerEntitlements(
        @PathParam("owner_key") @Verify(Owner.class) String ownerKey,
        @QueryParam("product") String productId,
        @QueryParam("matches") String matches,
        @QueryParam("attribute") List<KeyValueParamDTO> attrFilters,
        @Context PageRequest pageRequest) {

        Owner owner = findOwnerByKey(ownerKey);

        EntitlementFilterBuilder filters = EntitlementFinderUtil.createFilter(matches, attrFilters);
        Page<List<Entitlement>> entitlementsPage = entitlementCurator
            .listByOwner(owner, productId, filters, pageRequest);

        // Store the page for the LinkHeaderPostInterceptor
        ResteasyContext.pushContext(Page.class, entitlementsPage);

        List<EntitlementDTO> entitlementDTOs = new ArrayList<>();
        for (Entitlement entitlement : entitlementsPage.getPageData()) {
            entitlementDTOs.add(this.translator.translate(entitlement, EntitlementDTO.class));
        }

        return entitlementDTOs;
    }

    /**
     * Heals an Owner
     *
     * Starts an asynchronous healing for the given Owner. At the end of the
     * process the idea is that all of the consumers in the owned by the Owner
     * will be up to date.
     *
     * @param ownerKey id of the owner to be healed.
     * @return a JobDetail object
     * @httpcode 404
     * @httpcode 202
     */
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.WILDCARD)
    @Path("{owner_key}/entitlements")
    @ApiOperation(notes = "Starts an asynchronous healing for the given Owner." +
        " At the end of the process the idea is that all of the consumers " +
        "in the owned by the Owner will be up to date.", value = "Heal owner")
    @ApiResponses({ @ApiResponse(code = 404, message = "Owner not found") })
    public AsyncJobStatusDTO healEntire(
        @ApiParam("ownerKey id of the owner to be healed.") @PathParam("owner_key") @Verify(Owner.class)
        String ownerKey)
        throws JobException {

        Owner owner = findOwnerByKey(ownerKey);
        JobConfig config = HealEntireOrgJob.createJobConfig().setOwner(owner).setEntitleDate(new Date());

        AsyncJobStatus job = this.jobManager.queueJob(config);
        return this.translator.translate(job, AsyncJobStatusDTO.class);
    }

    /**
     * Retrieves a list of Support Levels for an Owner
     *
     * @param ownerKey id of the owner whose support levels are sought.
     * @return a set of String objects
     * @httpcode 404
     * @httpcode 200
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{owner_key}/servicelevels")
    @ApiOperation(notes = "Retrieves a list of Support Levels for an Owner", value = "Get Service Levels")
    @ApiResponses({ @ApiResponse(code = 404, message = "Owner not found") })
    public Set<String> ownerServiceLevels(
        @ApiParam("ownerKey id of the owner whose support levels are sought.")
        @PathParam("owner_key") @Verify(value = Owner.class,
        subResource = SubResource.SERVICE_LEVELS) String ownerKey,
        @Context Principal principal,
        @QueryParam("exempt") @DefaultValue("false") String exempt) {
        Owner owner = findOwnerByKey(ownerKey);

        if (principal.getType().equals("consumer")) {
            Consumer c = consumerCurator.findByUuid(principal.getName());
            if (c.isDev()) {
                Set<String> result = new HashSet<>();
                result.add("");
                return result;
            }
        }
        // test is on the string "true" and is case insensitive.
        return poolManager.retrieveServiceLevelsForOwner(owner.getId(), Boolean.parseBoolean(exempt));
    }

    /**
     * Retrieves a list of Activation Keys for an Owner
     *
     * @param ownerKey id of the owner whose keys are sought.
     * @return a list of Activation Key objects
     * @httpcode 404
     * @httpcode 200
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{owner_key}/activation_keys")
    @ApiOperation(notes = "Retrieves a list of Activation Keys for an Owner", value = "Owner Activation Keys",
        response = ActivationKeyDTO.class, responseContainer = "List")
    @ApiResponses({ @ApiResponse(code = 404, message = "Owner not found") })
    public CandlepinQuery<ActivationKeyDTO> ownerActivationKeys(
        @PathParam("owner_key") @Verify(Owner.class) String ownerKey,
        @QueryParam("name") String keyName) {
        Owner owner = findOwnerByKey(ownerKey);

        CandlepinQuery<ActivationKey> keys = this.activationKeyCurator.listByOwner(owner, keyName);
        return translator.translateQuery(keys, ActivationKeyDTO.class);
    }

    /**
     * Creates an Activation Key for the Owner
     *
     * @param ownerKey id of the owner whose keys are sought.
     * @return an Activation Key object
     * @httpcode 400
     * @httpcode 404
     * @httpcode 200
     */
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("{owner_key}/activation_keys")
    @ApiOperation(notes = "Creates an Activation Key for the Owner", value = "Create Activation Key")
    @ApiResponses({ @ApiResponse(code = 404, message = "Owner not found"),
        @ApiResponse(code = 400, message = "Invalid activation key") })
    public ActivationKeyDTO createActivationKey(@PathParam("owner_key") @Verify(Owner.class) String ownerKey,
        @ApiParam(name = "activation_key", required = true) ActivationKeyDTO dto) {

        validator.validateConstraints(dto);
        validator.validateCollectionElementsNotNull(dto::getContentOverrides, dto::getPools,
            dto::getProducts);

        if (StringUtils.isBlank(dto.getName())) {
            throw new BadRequestException(i18n.tr("Must provide a name for activation key."));
        }

        Matcher keyMatcher = AK_CHAR_FILTER.matcher(dto.getName());

        if (!keyMatcher.matches()) {
            throw new BadRequestException(
                i18n.tr("The activation key name \"{0}\" must be alphanumeric or " +
                    "include the characters \"-\" or \"_\"", dto.getName()));
        }

        if (dto.getContentOverrides() != null) {
            contentOverrideValidator.validate(dto.getContentOverrides());
        }

        Owner owner = findOwnerByKey(ownerKey);

        if (activationKeyCurator.getByKeyName(owner, dto.getName()) != null) {
            throw new BadRequestException(
                i18n.tr("The activation key name \"{0}\" is already in use for owner {1}",
                        dto.getName(), ownerKey));
        }

        serviceLevelValidator.validate(owner.getId(), dto.getServiceLevel());

        ActivationKey key = new ActivationKey();
        this.populateEntity(key, dto);
        key.setOwner(owner);

        ActivationKey newKey = activationKeyCurator.create(key);
        sink.emitActivationKeyCreated(newKey);

        return translator.translate(newKey, ActivationKeyDTO.class);
    }

    /**
     * Creates an Environment for an Owner
     *
     * @return an Environment object
     * @httpcode 404
     * @httpcode 200
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{owner_key}/environments")
    @ApiOperation(notes = "Creates an Environment for an Owner", value = "Create environment")
    @ApiResponses({ @ApiResponse(code = 404, message = "Owner not found")})
    public EnvironmentDTO createEnv(
        @PathParam("owner_key") @Verify(Owner.class) String ownerKey,
        @ApiParam(name = "environment", required = true) EnvironmentDTO envDTO) {

        Environment env = new Environment();
        NestedOwnerDTO ownerDTO = new NestedOwnerDTO().key(ownerKey);
        envDTO.setOwner(ownerDTO);
        populateEntity(env, envDTO);

        env = envCurator.create(env);
        return translator.translate(env, EnvironmentDTO.class);
    }

    /**
     * Retrieves a list of Environments for an Owner
     *
     * @param envName Optional environment name filter to search for.
     * @return a list of Environment objects
     * @httpcode 200
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{owner_key}/environments")
    @Wrapped(element = "environments")
    @ApiOperation(notes = "Retrieves a list of Environments for an Owner", value = "List environments",
        response = EnvironmentDTO.class, responseContainer = "List")
    @ApiResponses({ @ApiResponse(code = 404, message = "Owner not found")})
    public CandlepinQuery<EnvironmentDTO> listEnvironments(@PathParam("owner_key")
        @Verify(Owner.class) String ownerKey,
        @ApiParam("Environment name filter to search for.")
        @QueryParam("name") String envName) {
        Owner owner = findOwnerByKey(ownerKey);
        CandlepinQuery<Environment> query = envName == null ?
            envCurator.listForOwner(owner) :
            envCurator.listForOwnerByName(owner, envName);
        return translator.translateQuery(query, EnvironmentDTO.class);
    }

    /**
     * Sets the Log Level for an Owner
     *
     * @param ownerKey
     * @param level
     * @return an Owner object
     */
    @PUT
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.WILDCARD)
    @Path("{owner_key}/log")
    @ApiOperation(notes = "Sets the Log Level for an Owner", value = "Set Log Level")
    @ApiResponses({ @ApiResponse(code = 404, message = "Owner not found")})
    public OwnerDTO setLogLevel(@PathParam("owner_key") String ownerKey,
        @QueryParam("level") @DefaultValue("DEBUG")
        @ApiParam(allowableValues = "ALL, TRACE, DEBUG, INFO, WARN, ERROR, OFF") String level) {

        Owner owner = findOwnerByKey(ownerKey);

        Level logLevel = Level.toLevel(level, null);
        if (logLevel == null) {
            throw new BadRequestException(i18n.tr("{0} is not a valid log level", level));
        }

        owner.setLogLevel(logLevel.toString());
        owner = ownerCurator.merge(owner);

        return this.translator.translate(owner, OwnerDTO.class);
    }

    /**
     * Remove the Log Level of an Owner
     *
     * @param ownerKey
     */
    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{owner_key}/log")
    @ApiOperation(notes = "Remove the Log Level of an Owner", value = "Remove Log Level")
    @ApiResponses({ @ApiResponse(code = 404, message = "Owner not found")})
    public void deleteLogLevel(@PathParam("owner_key") String ownerKey) {
        Owner owner = findOwnerByKey(ownerKey);
        owner.setLogLevel((String) null);
        ownerCurator.merge(owner);
    }

    /**
     * Retrieve a list of Consumers for the Owner
     *
     * @param ownerKey id of the owner whose consumers are sought.
     * @return a list of Consumer objects
     * @httpcode 400
     * @httpcode 404
     * @httpcode 200
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{owner_key}/consumers")
    @SuppressWarnings("checkstyle:indentation")
    @ApiOperation(notes = "Retrieve a list of Consumers for the Owner", value = "List Consumers",
        response = ConsumerDTOArrayElement.class, responseContainer = "list")
    @ApiResponses({
        @ApiResponse(code = 404, message = "Owner not found"),
        @ApiResponse(code = 400, message = "Invalid request")
    })
    public CandlepinQuery<ConsumerDTOArrayElement> listConsumers(
        @PathParam("owner_key")
        @Verify(value = Owner.class, subResource = SubResource.CONSUMERS) String ownerKey,
        @QueryParam("username") String userName,
        @QueryParam("type") Set<String> typeLabels,
        @QueryParam("uuid") @Verify(value = Consumer.class, nullable = true) List<String> uuids,
        @QueryParam("hypervisor_id") List<String> hypervisorIds,
        @QueryParam("fact") List<KeyValueParamDTO> attrFilters,
        @QueryParam("sku") List<String> skus,
        @QueryParam("subscription_id") List<String> subscriptionIds,
        @QueryParam("contract") List<String> contracts,
        @Context PageRequest pageRequest) {

        Owner owner = findOwnerByKey(ownerKey);
        List<ConsumerType> types = consumerTypeValidator.findAndValidateTypeLabels(typeLabels);

        CandlepinQuery<Consumer> query = this.consumerCurator.searchOwnerConsumers(
            owner, userName, types, uuids, hypervisorIds, attrFilters, skus,
            subscriptionIds, contracts);
        return translator.translateQuery(query, ConsumerDTOArrayElement.class);
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{owner_key}/consumers/count")
    @SuppressWarnings("checkstyle:indentation")
    @ApiOperation(notes = "Retrieve a count of Consumers for the Owner", value = "consumers count")
    @ApiResponses({
        @ApiResponse(code = 404, message = "Owner not found"),
        @ApiResponse(code = 400, message = "Invalid request")
    })
    public int countConsumers(
        @PathParam("owner_key")
        @Verify(value = Owner.class, subResource = SubResource.CONSUMERS) String ownerKey,
        @QueryParam("type") Set<String> typeLabels,
        @QueryParam("sku") List<String> skus,
        @QueryParam("subscription_id") List<String> subscriptionIds,
        @QueryParam("contract") List<String> contracts) {

        this.findOwnerByKey(ownerKey);
        consumerTypeValidator.findAndValidateTypeLabels(typeLabels);

        return consumerCurator.countConsumers(ownerKey, typeLabels, skus, subscriptionIds, contracts);
    }

    /**
     * Retrieves a list of Pools for an Owner
     *
     * @param ownerKey id of the owner whose entitlement pools are sought.
     * @param matches Find pools matching the given pattern in a variety of fields.
     * * and ? wildcards are supported.
     * @return a list of Pool objects
     * @httpcode 400
     * @httpcode 404
     * @httpcode 200
     */
    @Transactional
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{owner_key}/pools")
    @SuppressWarnings("checkstyle:indentation")
    @ApiOperation(notes = "Retrieves a list of Pools for an Owner. This endpoint " +
        "supports paging with query parameters. For more details please visit " +
        "https://www.candlepinproject.org/docs/candlepin/pagination.html#paginating-results-from-candlepin",
        value = "List Pools")
    @ApiResponses({
        @ApiResponse(code = 404, message = "Owner not found"),
        @ApiResponse(code = 400, message = "Invalid request")
    })
    public List<PoolDTO> listPools(
        @PathParam("owner_key") @Verify(value = Owner.class, subResource = SubResource.POOLS) String ownerKey,
        @QueryParam("consumer") String consumerUuid,
        @QueryParam("activation_key") String activationKeyName,
        @QueryParam("product") String productId,
        @QueryParam("subscription") String subscriptionId,
        @ApiParam("Include pools that are not suited to the unit's facts.")
        @QueryParam("listall") @DefaultValue("false") boolean listAll,
        @ApiParam("Date to use as current time for lookup criteria. Defaults" +
                " to current date if not specified.")
        @QueryParam("activeon") @DefaultValue(DateFormat.NOW) @DateFormat Date activeOn,
        @ApiParam("Find pools matching the given pattern in a variety of fields;" +
                " * and ? wildcards are supported; may be specified multiple times")
        @QueryParam("matches") List<String> matches,
        @ApiParam("The attributes to return based on the specified types.")
        @QueryParam("attribute") List<KeyValueParamDTO> attrFilters,
        @ApiParam("When set to true, it will add future dated pools to the result, " +
                "based on the activeon date.")
        @QueryParam("add_future") @DefaultValue("false") boolean addFuture,
        @ApiParam("When set to true, it will return only future dated pools to the result, " +
                "based on the activeon date.")
        @QueryParam("only_future") @DefaultValue("false") boolean onlyFuture,
        @ApiParam("Will only return pools with a start date after the supplied date. " +
                "Overrides the activeOn date.")
        @QueryParam("after") @DateFormat Date after,
        @ApiParam("One or more pool IDs to use to filter the output; only pools with IDs matching " +
                "those provided will be returned; may be specified multiple times")
        @QueryParam("poolid") List<String> poolIds,
        @Context Principal principal,
        @Context PageRequest pageRequest) {

        Owner owner = findOwnerByKey(ownerKey);

        Consumer c = null;
        if (consumerUuid != null) {
            c = consumerCurator.findByUuid(consumerUuid);
            if (c == null) {
                throw new NotFoundException(i18n.tr("Unit: {0} not found",
                    consumerUuid));
            }

            if (!c.getOwnerId().equals(owner.getId())) {
                throw new BadRequestException(
                    "Consumer specified does not belong to owner on path");
            }

            if (!principal.canAccess(c, SubResource.NONE, Access.READ_ONLY)) {
                throw new ForbiddenException(i18n.tr("User {0} cannot access consumer {1}",
                    principal.getPrincipalName(), c.getUuid()));
            }
        }

        ActivationKey key = null;
        if (activationKeyName != null) {
            key = activationKeyCurator.getByKeyName(owner, activationKeyName);
            if (key == null) {
                throw new BadRequestException(
                    i18n.tr("ActivationKey with id {0} could not be found.", activationKeyName));
            }
        }

        if (addFuture && onlyFuture) {
            throw new BadRequestException(
                i18n.tr("The flags add_future and only_future cannot be used at the same time."));
        }

        if (after != null && (addFuture || onlyFuture)) {
            throw new BadRequestException(
                    i18n.tr("The flags add_future and only_future cannot be used with the parameter after."));
        }

        if (after != null) {
            activeOn = null;
        }

        // Process the filters passed for the attributes
        PoolFilterBuilder poolFilters = new PoolFilterBuilder();
        for (KeyValueParamDTO filterParam : attrFilters) {
            poolFilters.addAttributeFilter(filterParam.getKey(), filterParam.getValue());
        }

        if (matches != null) {
            matches.stream()
                .filter(elem -> elem != null && !elem.isEmpty())
                .forEach(elem -> poolFilters.addMatchesFilter(elem));
        }

        if (poolIds != null && !poolIds.isEmpty()) {
            poolFilters.addIdFilters(poolIds);
        }

        Page<List<Pool>> page = poolManager.listAvailableEntitlementPools(
            c, key, owner.getId(), productId, subscriptionId, activeOn, listAll, poolFilters, pageRequest,
            addFuture, onlyFuture, after);

        List<Pool> poolList = page.getPageData();
        calculatedAttributesUtil.setCalculatedAttributes(poolList, activeOn);
        calculatedAttributesUtil.setQuantityAttributes(poolList, c, activeOn);

        // Store the page for the LinkHeaderResponseFilter
        ResteasyContext.pushContext(Page.class, page);

        List<PoolDTO> poolDTOs = new ArrayList<>();
        for (Pool pool : poolList) {
            poolDTOs.add(translator.translate(pool, PoolDTO.class));
        }

        return poolDTOs;
    }

    /**
     * Retrieves a list of Subscriptions for an Owner
     *
     * @return a list of Subscription objects
     * @httpcode 404
     * @httpcode 200
     */
    @GET
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{owner_key}/subscriptions")
    @ApiOperation(notes = "Retrieves a list of Subscriptions for an Owner", value = "List Subscriptions")
    @ApiResponses({ @ApiResponse(code = 404, message = "Owner not found") })
    public List<Subscription> getSubscriptions(@PathParam("owner_key") String ownerKey) {
        Owner owner = this.findOwnerByKey(ownerKey);

        List<Subscription> subscriptions = new LinkedList<>();

        for (Pool pool : this.poolManager.listPoolsByOwner(owner).list()) {
            SourceSubscription srcsub = pool.getSourceSubscription();

            if (srcsub != null && "master".equalsIgnoreCase(srcsub.getSubscriptionSubKey())) {
                subscriptions.add(this.poolManager.fabricateSubscriptionFromPool(pool));
            }
        }

        return subscriptions;
    }

    /**
     * Creates a Subscription for an Owner
     *
     * DEPRECATED: Please create pools directly with POST /pools.
     *
     * @deprecated Please create pools directly with POST /pools.
     * @return a Subscription object
     * @httpcode 404
     * @httpcode 200
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{owner_key}/subscriptions")
    @Deprecated
    @ApiOperation(notes = "Creates a Subscription for an Owner DEPRECATED: Please create " +
        "pools directly with POST /pools.", value = "Create Subscription")
    @ApiResponses({ @ApiResponse(code = 404, message = "Owner not found") })
    public Subscription createSubscription(
        @PathParam("owner_key") @Verify(Owner.class) String ownerKey,
        Subscription subscription) {

        // Correct owner & products
        Owner owner = findOwnerByKey(ownerKey);
        subscription.setOwner(owner);

        subscription = resolverUtil.resolveSubscription(subscription);

        if (subscription.getId() == null) {
            subscription.setId(Util.generateDbUUID());
        }

        poolManager.createAndEnrichPools(subscription);
        return subscription;
    }

    /**
     * Updates a Subscription for an Owner
     *
     * @deprecated Please update pools directly with POST /pools.
     * @httpcode 404
     * @httpcode 200
     */
    @PUT
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/subscriptions")
    @Deprecated
    @ApiOperation(
        notes = "Updates a Subscription for an Owner.  Please " + "update pools directly with POST /pools.",
        value = "Update Subscription")
    @ApiResponses({ @ApiResponse(code = 404, message = "Owner not found") })
    public void updateSubscription(
        @ApiParam(name = "subscription", required = true) Subscription subscription) {

        throw new ResourceMovedException("owners/{owner_key}/pools");
    }

    /**
     * Refreshes the Pools for an Owner
     * <p>
     * 'Tickle' an owner to have all of their entitlement pools synced with
     * their subscriptions. This method (and the one below may not be entirely
     * RESTful, as the updated data is not supplied as an argument.
     *
     * This API call is only relevant in a top level hosted deployment where subscriptions
     * and products are sourced from adapters. Calling this in an on-site deployment
     * is just a no-op.
     *
     * @param ownerKey unique id key of the owner whose pools should be updated
     * @return a JobDetail object
     * @httpcode 404
     * @httpcode 202
     */
    @PUT
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.WILDCARD)
    @Path("{owner_key}/subscriptions")
    @ApiOperation(notes = "Tickle an owner to have all of their entitlement pools synced with their " +
        "subscriptions. This method (and the one below may not be entirely RESTful, " +
        "as the updated data is not supplied as an argument. " +
        "This API call is only relevant in a top level hosted deployment where " +
        "subscriptions and products are sourced from adapters. Calling this in " +
        "an on-site deployment is just a no-op.", value = "Update Subscription")
    @ApiResponses({ @ApiResponse(code = 404, message = "Owner not found"),
        @ApiResponse(code = 202, message = "") })
    public AsyncJobStatusDTO refreshPools(
        @PathParam("owner_key") String ownerKey,
        @QueryParam("auto_create_owner") @DefaultValue("false") Boolean autoCreateOwner,
        @QueryParam("lazy_regen") @DefaultValue("true") Boolean lazyRegen) throws JobException {

        Owner owner = ownerCurator.getByKey(ownerKey);
        if (owner == null) {
            if (autoCreateOwner && ownerService.isOwnerKeyValidForCreation(ownerKey)) {
                owner = this.ownerCurator.create(new Owner(ownerKey, ownerKey));
            }
            else {
                throw new NotFoundException(i18n.tr("owner with key: {0} was not found.", ownerKey));
            }
        }

        if (config.getBoolean(ConfigProperties.STANDALONE)) {
            log.warn("Ignoring refresh pools request due to standalone config.");
            return null;
        }

        JobConfig config = RefreshPoolsJob.createJobConfig()
            .setOwner(owner)
            .setLazyRegeneration(lazyRegen);

        AsyncJobStatus job = this.jobManager.queueJob(config);
        return this.translator.translate(job, AsyncJobStatusDTO.class);
    }

    /**
     * Creates a custom pool for an Owner. Floating pools are not tied to any
     * upstream subscription, and are most commonly used for custom content
     * delivery in Satellite.
     * Also helps in on-site deployment testing
     *
     * @return a Pool object
     * @httpcode 404
     * @httpcode 200
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{owner_key}/pools")
    @ApiOperation(notes = "Creates a custom pool for an Owner. Floating pools are not tied to any " +
        "upstream subscription, and are most commonly used for custom content delivery " +
        "in Satellite. Also helps in on-site deployment testing", value = "Create Pool")
    @ApiResponses({ @ApiResponse(code = 404, message = "Owner not found") })
    public PoolDTO createPool(@PathParam("owner_key") @Verify(Owner.class) String ownerKey,
        @ApiParam(name = "pool", required = true) PoolDTO inputPoolDTO) {

        log.info("Creating custom pool for owner {}: {}", ownerKey, inputPoolDTO);

        this.validator.validateConstraints(inputPoolDTO);
        this.validator.validateCollectionElementsNotNull(
            inputPoolDTO::getDerivedProvidedProducts, inputPoolDTO::getProvidedProducts);

        Pool pool = new Pool();

        // Correct owner
        Owner owner = findOwnerByKey(ownerKey);
        pool.setOwner(owner);

        // Populate the rest of the pool
        this.populateEntity(pool, inputPoolDTO);

        pool.setContractNumber(inputPoolDTO.getContractNumber());
        pool.setOrderNumber(inputPoolDTO.getOrderNumber());
        pool.setAccountNumber(inputPoolDTO.getAccountNumber());
        pool.setUpstreamPoolId(inputPoolDTO.getUpstreamPoolId());
        pool.setSubscriptionSubKey(inputPoolDTO.getSubscriptionSubKey());
        pool.setSubscriptionId(inputPoolDTO.getSubscriptionId());

        if (inputPoolDTO.getProductId() == null || inputPoolDTO.getProductId().isEmpty()) {
            throw new BadRequestException(i18n.tr("Pool product ID not specified"));
        }

        pool.setProduct(findProduct(pool.getOwner(), inputPoolDTO.getProductId()));

        if (inputPoolDTO.getDerivedProductId() != null) {
            pool.setDerivedProduct(findProduct(pool.getOwner(), inputPoolDTO.getDerivedProductId()));
        }

        if (inputPoolDTO.getSourceEntitlement() != null) {
            pool.setSourceEntitlement(findEntitlement(inputPoolDTO.getSourceEntitlement().getId()));
        }

        pool = poolManager.createAndEnrichPools(pool);
        return this.translator.translate(pool, PoolDTO.class);
    }

    /**
     * Updates a pool for an Owner.
     * assumes this is a normal pool, and errors out otherwise cause we cannot
     * create master pools from bonus pools
     *
     * @httpcode 404
     * @httpcode 200
     */
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{owner_key}/pools")
    @ApiOperation(
        notes = "Updates a pool for an Owner. assumes this is a normal pool, and " +
        "errors out otherwise cause we cannot create master pools from bonus pools ",
        value = "Update Pool")
    @ApiResponses({ @ApiResponse(code = 404, message = "Owner not found") })
    public void updatePool(@PathParam("owner_key") @Verify(Owner.class) String ownerKey,
        @ApiParam(name = "pool", required = true) PoolDTO newPoolDTO) {

        this.validator.validateConstraints(newPoolDTO);
        this.validator.validateCollectionElementsNotNull(
            newPoolDTO::getDerivedProvidedProducts, newPoolDTO::getProvidedProducts);

        Pool currentPool = this.poolManager.get(newPoolDTO.getId());
        if (currentPool == null) {
            throw new NotFoundException(
                i18n.tr("Unable to find a pool with the ID \"{0}\"", newPoolDTO.getId()));
        }

        // Verify the existing pool belongs to the specified owner
        // If the pool isn't valid for this owner, we'll pretend it doesn't exist to avoid any
        // potential security concerns.
        if (currentPool.getOwner() == null || !ownerKey.equals(currentPool.getOwner().getKey())) {
            throw new NotFoundException(
                i18n.tr("Pool \"{0}\" does not belong to the specified owner \"{1}\"",
                currentPool.getId(), ownerKey));
        }

        // Verify the pool type is one that allows modifications
        if (currentPool.getType() != PoolType.NORMAL) {
            throw new BadRequestException(i18n.tr("Cannot update bonus pools, as they are auto generated"));
        }

        Pool newPool = new Pool();
        this.populateEntity(newPool, newPoolDTO);

        // Owner & id have already been validated/resolved.
        newPool.setOwner(currentPool.getOwner());
        newPool.setId(newPoolDTO.getId());

        /* These are @JsonIgnored. If a client creates a pool and subsequently
         * wants to update it , we need to ensure Products are set appropriately.
         */
        newPool.setProduct(currentPool.getProduct());
        newPool.setDerivedProduct(currentPool.getDerivedProduct());

        // Forcefully set fields we don't allow the client to change.
        newPool.setSourceSubscription(currentPool.getSourceSubscription());
        newPool.setContractNumber(currentPool.getContractNumber());
        newPool.setAccountNumber(currentPool.getAccountNumber());
        newPool.setOrderNumber(currentPool.getOrderNumber());
        newPool.setUpstreamPoolId(currentPool.getUpstreamPoolId());
        newPool.setUpstreamEntitlementId(currentPool.getUpstreamEntitlementId());
        newPool.setUpstreamConsumerId(currentPool.getUpstreamConsumerId());
        newPool.setCertificate(currentPool.getCertificate());
        newPool.setCdn(currentPool.getCdn());

        // Update fields the client has changed, or otherwise set the current value.
        if (newPoolDTO.getStartDate() == null) {
            newPool.setStartDate(currentPool.getStartDate());
        }

        if (newPoolDTO.getEndDate() == null) {
            newPool.setEndDate(currentPool.getEndDate());
        }

        if (newPoolDTO.getQuantity() == null) {
            newPool.setQuantity(currentPool.getQuantity());
        }

        if (newPoolDTO.getAttributes() == null) {
            newPool.setAttributes(currentPool.getAttributes());
        }

        if (newPoolDTO.getProvidedProducts() == null) {
            newPool.setProvidedProducts(currentPool.getProvidedProducts());
        }

        if (newPoolDTO.getDerivedProvidedProducts() == null) {
            newPool.setDerivedProvidedProducts(currentPool.getDerivedProvidedProducts());
        }

        // Apply changes to the pool and its derived pools
        this.poolManager.updateMasterPool(newPool);
    }

    /**
     * Removes Imports for an Owner
     * <p>
     * Cleans out all imported subscriptions and triggers a background refresh pools.
     * Link to an upstream distributor is removed for the owner, so they can import from
     * another distributor. Other owners can also now import the manifests originally
     * used in this owner.
     * <p>
     * This call does not differentiate between any specific import, it just
     * destroys all subscriptions with an upstream pool ID, essentially anything from
     * an import. Custom subscriptions will be left alone.
     * <p>
     * Imports do carry rules and product information which is global to the candlepin
     * server. This import data is *not* undone, we assume that updates to this data
     * can be safely kept.
     *
     * @return a JobDetail object
     * @httpcode 404
     * @httpcode 200
     */
    @DELETE
    @Path("{owner_key}/imports")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(notes = "Removes Imports for an Owner. Cleans out all imported subscriptions " +
        "and triggers a background refresh pools. Link to an upstream distributor is " +
        "removed for the owner, so they can import from another distributor. Other " +
        "owners can also now import the manifests originally used in this owner. This  " +
        "call does not differentiate between any specific import, it just destroys all " +
        "subscriptions with an upstream pool ID, essentially anything from an import." +
        " Custom subscriptions will be left alone. Imports do carry rules and product " +
        "information which is global to the candlepin server. This import data is *not* " +
        "undone, we assume that updates to this data can be safely kept. ", value = "Undo Imports")
    @ApiResponses({ @ApiResponse(code = 404, message = "Owner not found")})
    public AsyncJobStatusDTO undoImports(
        @PathParam("owner_key") @Verify(Owner.class) String ownerKey, @Context Principal principal)
        throws JobException {

        Owner owner = findOwnerByKey(ownerKey);

        if (this.exportCurator.getByTypeAndOwner(ExporterMetadata.TYPE_PER_USER, owner) == null) {
            throw new NotFoundException("No import found for owner " + ownerKey);
        }

        JobConfig config = UndoImportsJob.createJobConfig()
            .setOwner(owner);

        AsyncJobStatus job = this.jobManager.queueJob(config);
        return this.translator.translate(job, AsyncJobStatusDTO.class);
    }

    /**
     * Imports a manifest zip file for the given organization.
     *
     * This will bring in any products, content, and subscriptions that were assigned to
     * the distributor who generated the manifest.
     *
     * @deprecated use GET /owners/:owner_key/imports/async
     *
     * @return a ImportRecord object if the import is successful.
     * @httpcode 400
     * @httpcode 404
     * @httpcode 500
     * @httpcode 200
     * @httpcode 409
     */
    @POST
    @Path("{owner_key}/imports")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @ApiOperation(
        notes = "Imports a manifest zip file for the given organization. " +
        "This will bring in any products, content, and subscriptions that were " +
        "assigned to the distributor who generated the manifest.", value = "Import Manifest")
    @ApiResponses({ @ApiResponse(code = 400, message = ""),
        @ApiResponse(code = 404, message = "Owner not found"), @ApiResponse(code = 500, message = ""),
        @ApiResponse(code = 409, message = "") })
    @Deprecated
    public ImportRecordDTO importManifest(
        @PathParam("owner_key") @Verify(Owner.class) String ownerKey,
        @QueryParam("force") String[] overrideConflicts,
        MultipartInput input) {

        ConflictOverrides overrides = processConflictOverrideParams(overrideConflicts);
        UploadMetadata fileData = new UploadMetadata();
        Owner owner = findOwnerByKey(ownerKey);

        try {
            fileData = getArchiveFromResponse(input);
            ImportRecord record = manifestManager.importManifest(owner, fileData.getData(),
                fileData.getUploadedFilename(), overrides);

            return this.translator.translate(record, ImportRecordDTO.class);
        }
        catch (IOException e) {
            log.error("Reading error during importing", e);
            manifestManager.recordImportFailure(owner, e, fileData.getUploadedFilename());
            throw new IseException(i18n.tr("Error reading export archive"), e);
        }
        // These come back with internationalized messages, so we can transfer:
        catch (SyncDataFormatException e) {
            log.error("Format error of the data in a manifest", e);
            manifestManager.recordImportFailure(owner, e, fileData.getUploadedFilename());
            throw new BadRequestException(e.getMessage(), e);
        }
        catch (ImporterException e) {
            log.error("Problem with archive", e);
            manifestManager.recordImportFailure(owner, e, fileData.getUploadedFilename());
            throw new IseException(e.getMessage(), e);
        }
        // Grab candlepin exceptions to record the error and then rethrow
        // to pass on the http return code
        catch (CandlepinException e) {
            log.error("Recording import failure", e);
            manifestManager.recordImportFailure(owner, e, fileData.getUploadedFilename());
            throw e;
        }
        finally {
            log.info("Import attempt completed for owner {}", owner.getDisplayName());
        }
    }

    /**
     * Initiates an asynchronous manifest import for the given organization. The details of
     * the started job can be obtained via the {@link JobResource}.
     *
     * This will bring in any products, content, and subscriptions that were assigned to
     * the distributor who generated the manifest.
     *
     * @return a JobDetail object representing the newly started {@link ImportJob}.
     * @httpcode 400
     * @httpcode 404
     * @httpcode 500
     * @httpcode 200
     * @httpcode 409
     */
    @POST
    @Path("{owner_key}/imports/async")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @ApiOperation(
        notes = "Initiates an asynchronous manifest import for the given organization. " +
        "This will bring in any products, content, and subscriptions that were " +
        "assigned to the distributor who generated the manifest.",
        value = "Import Manifest Asynchronously")
    @ApiResponses({
        @ApiResponse(code = 400, message = ""),
        @ApiResponse(code = 404, message = "Owner not found"),
        @ApiResponse(code = 500, message = ""),
        @ApiResponse(code = 409, message = "")})
    public AsyncJobStatusDTO importManifestAsync(
        @PathParam("owner_key") @Verify(Owner.class) String ownerKey,
        @QueryParam("force") String[] overrideConflicts,
        MultipartInput input) throws JobException {

        ConflictOverrides overrides = processConflictOverrideParams(overrideConflicts);
        UploadMetadata fileData = new UploadMetadata();
        Owner owner = findOwnerByKey(ownerKey);

        try {
            fileData = getArchiveFromResponse(input);
            String archivePath = fileData.getData().getAbsolutePath();
            log.info("Running async import of archive {} for owner {}", archivePath, owner.getDisplayName());
            JobConfig config =  manifestManager.importManifestAsync(owner, fileData.getData(),
                fileData.getUploadedFilename(), overrides);

            AsyncJobStatus job = this.jobManager.queueJob(config);
            return this.translator.translate(job, AsyncJobStatusDTO.class);
        }
        catch (IOException e) {
            manifestManager.recordImportFailure(owner, e, fileData.getUploadedFilename());
            throw new IseException(i18n.tr("Error reading export archive"), e);
        }
        catch (ManifestFileServiceException e) {
            manifestManager.recordImportFailure(owner, e, fileData.getUploadedFilename());
            throw new IseException(i18n.tr("Error storing uploaded archive for asynchronous processing."), e);
        }
        catch (CandlepinException e) {
            manifestManager.recordImportFailure(owner, e, fileData.getUploadedFilename());
            throw e;
        }
    }

    /**
     * Retrieves a list of Import Records for an Owner
     *
     * @return a list of ImportRecord objects
     * @httpcode 404
     * @httpcode 200
     */
    @GET
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{owner_key}/imports")
    @ApiOperation(notes = " Retrieves a list of Import Records for an Owner", value = "Get Imports",
        response = ImportRecord.class, responseContainer = "list")
    @ApiResponses({ @ApiResponse(code = 404, message = "Owner not found") })
    public CandlepinQuery<ImportRecord> getImports(
        @PathParam("owner_key") @Verify(Owner.class) String ownerKey) {
        Owner owner = findOwnerByKey(ownerKey);

        return this.importRecordCurator.findRecords(owner);
    }

    @ApiOperation(notes = "Retrieves the system purpose settings available to an owner", value =
        "getSyspurpose")
    @ApiResponses({@ApiResponse(code = 404, message = "Owner not found")})
    @GET
    @Path("{owner_key}/system_purpose")
    @Produces(MediaType.APPLICATION_JSON)
    public SystemPurposeAttributesDTO getSyspurpose(
        @PathParam("owner_key")
        @Verify(value = Owner.class, subResource = SubResource.POOLS, require = Access.READ_ONLY)
        String ownerKey) {

        Owner owner = findOwnerByKey(ownerKey);
        List<Product> products = ownerProductCurator.getProductsByOwner(owner).list();

        Map<String, Set<String>> dtoMap = new HashMap<>();
        Arrays.stream(SystemPurposeAttributeType.values())
            .forEach(x -> dtoMap.put(x.toString(), new LinkedHashSet<>()));

        for (Product p : products) {
            for (SystemPurposeAttributeType type : SystemPurposeAttributeType.values()) {
                String purposeValue = p.getAttributeValue(type.toString());
                Set<String> purposes = new LinkedHashSet<>();
                if (purposeValue != null) {
                    purposes = new LinkedHashSet<>(Arrays.asList(purposeValue.split("\\s*,\\s*")));
                }
                dtoMap.get(type.toString()).addAll(purposes);
            }
        }

        SystemPurposeAttributesDTO dto = new SystemPurposeAttributesDTO();
        dto.setOwner(translator.translate(owner, NestedOwnerDTO.class));
        dto.setSystemPurposeAttributes(dtoMap);
        return dto;
    }

    @ApiOperation(notes = "Retrieves an aggregate of the system purpose settings of the owner's consumers",
        value = "getConsumersSyspurpose")
    @ApiResponses({@ApiResponse(code = 404, message = "Owner not found")})
    @GET
    @Path("{owner_key}/consumers_system_purpose")
    @Produces(MediaType.APPLICATION_JSON)
    public SystemPurposeAttributesDTO getConsumersSyspurpose(
        @PathParam("owner_key") @Verify(Owner.class) String ownerKey) {
        Owner owner = findOwnerByKey(ownerKey);
        List<String> consumerRoles = this.consumerCurator.getDistinctSyspurposeRolesByOwner(owner);
        List<String> consumerUsages = this.consumerCurator.getDistinctSyspurposeUsageByOwner(owner);
        List<String> consumerSLAs = this.consumerCurator.getDistinctSyspurposeServicelevelByOwner(owner);
        List<String> consumerAddons = this.consumerCurator.getDistinctSyspurposeAddonsByOwner(owner);

        Map<String, Set<String>> dtoMap = new HashMap<>();
        Arrays.stream(SystemPurposeAttributeType.values())
            .forEach(x -> dtoMap.put(x.toString(), new LinkedHashSet<>()));

        dtoMap.get(SystemPurposeAttributeType.ROLES.toString()).addAll(consumerRoles);
        dtoMap.get(SystemPurposeAttributeType.USAGE.toString()).addAll(consumerUsages);
        dtoMap.get(SystemPurposeAttributeType.SERVICE_LEVEL.toString()).addAll(consumerSLAs);
        dtoMap.get(SystemPurposeAttributeType.ADDONS.toString()).addAll(consumerAddons);

        SystemPurposeAttributesDTO dto = new SystemPurposeAttributesDTO();
        dto.setOwner(translator.translate(owner, NestedOwnerDTO.class));
        dto.setSystemPurposeAttributes(dtoMap);
        return dto;
    }

    /**
     * Creates an Ueber Entitlement Certificate
     *
     * @return an EntitlementCertificate object
     * @httpcode 400
     * @httpcode 404
     * @httpcode 200
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{owner_key}/uebercert")
    @ApiOperation(notes = "Creates an Ueber Entitlement Certificate. If a certificate " +
        "already exists, it will be regenerated.",
        value = "Create Ueber Entitlement Certificate")
    @ApiResponses({
        @ApiResponse(code = 404, message = "Owner not found"),
        @ApiResponse(code = 400, message = "") })
    public UeberCertificateDTO createUeberCertificate(@Context Principal principal,
        @Verify(Owner.class) @PathParam("owner_key") String ownerKey) {

        UeberCertificate ueberCert = ueberCertGenerator.generate(ownerKey, principal);

        return this.translator.translate(ueberCert, UeberCertificateDTO.class);
    }

    /**
     * Retrieves the Ueber Entitlement Certificate
     *
     * @return an EntitlementCertificate object
     * @httpcode 404
     * @httpcode 200
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{owner_key}/uebercert")
    @ApiOperation(notes = "Retrieves the Ueber Entitlement Certificate",
        value = "Get Ueber Entitlement Certificate")
    @ApiResponses({ @ApiResponse(code = 404, message = "Owner not found") })
    public UeberCertificateDTO getUeberCertificate(@Context Principal principal,
        @Verify(Owner.class) @PathParam("owner_key") String ownerKey) {

        Owner owner = this.findOwnerByKey(ownerKey);
        UeberCertificate ueberCert = ueberCertCurator.findForOwner(owner);
        if (ueberCert == null) {
            throw new NotFoundException(i18n.tr(
                "uber certificate for owner {0} was not found. Please generate one.", owner.getKey()));
        }

        return this.translator.translate(ueberCert, UeberCertificateDTO.class);
    }

    /**
     * Retrieves a list of Upstream Consumers for an Owner
     *
     * @return a list of UpstreamConsumer objects
     * @httpcode 404
     * @httpcode 200
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{owner_key}/upstream_consumers")
    @ApiOperation(notes = " Retrieves a list of Upstream Consumers for an Owner",
        value = "Get Upstream Consumers")
    @ApiResponses({ @ApiResponse(code = 404, message = "Owner not found") })
    public List<UpstreamConsumerDTO> getUpstreamConsumers(@Context Principal principal,
        @Verify(Owner.class) @PathParam("owner_key") String ownerKey) {

        Owner owner = this.findOwnerByKey(ownerKey);
        UpstreamConsumer consumer = owner.getUpstreamConsumer();
        UpstreamConsumerDTO dto =
            this.translator.translate(consumer, UpstreamConsumerDTO.class);

        // returning as a list for future proofing. today we support one, but
        // users of this api want to protect against having to change their code
        // when multiples are supported.
        return Arrays.asList(dto);
    }

    /**
     * Retrieves a list of Hypervisors for an Owner
     *
     * @param ownerKey
     * @param hypervisorIds
     * @return a list of Consumer objects
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{owner_key}/hypervisors")
    @ApiOperation(notes = "Retrieves a list of Hypervisors for an Owner", value = "Get Hypervisors",
        response = ConsumerDTOArrayElement.class, responseContainer = "list")
    @ApiResponses({ @ApiResponse(code = 404, message = "Owner not found") })
    public CandlepinQuery<ConsumerDTOArrayElement> getHypervisors(
        @PathParam("owner_key") @Verify(Owner.class) String ownerKey,
        @QueryParam("hypervisor_id") List<String> hypervisorIds) {

        Owner owner = ownerCurator.getByKey(ownerKey);
        CandlepinQuery<Consumer> query = (hypervisorIds == null || hypervisorIds.isEmpty()) ?
            this.consumerCurator.getHypervisorsForOwner(owner.getId()) :
            this.consumerCurator.getHypervisorsBulk(hypervisorIds, owner.getId());
        return translator.translateQuery(query, ConsumerDTOArrayElement.class);
    }

    private ConflictOverrides processConflictOverrideParams(String[] overrideConflicts) {
        if (overrideConflicts.length == 1) {
            /*
             * For backward compatibility, look for force=true and if found,
             * treat it just like what it used to mean, ignore an old manifest
             * creation date.
             */
            if (overrideConflicts[0].equalsIgnoreCase("true")) {
                overrideConflicts = new String [] { "MANIFEST_OLD" };
            }
            else if (overrideConflicts[0].equalsIgnoreCase("false")) {
                overrideConflicts = new String [] {};
            }
        }
        if (log.isDebugEnabled()) {
            for (String s : overrideConflicts) {
                log.debug("Forcing conflict if encountered: " + s);
            }
        }

        ConflictOverrides overrides = null;
        try {
            overrides = new ConflictOverrides(overrideConflicts);
        }
        catch (IllegalArgumentException e) {
            throw new BadRequestException(i18n.tr("Unknown conflict to force"));
        }

        return overrides;
    }

    private UploadMetadata getArchiveFromResponse(MultipartInput input) throws IOException {
        String filename = "";
        InputPart part = input.getParts().get(0);
        MultivaluedMap<String, String> headers = part.getHeaders();
        String contDis = headers.getFirst("Content-Disposition");
        StringTokenizer st = new StringTokenizer(contDis, ";");
        while (st.hasMoreTokens()) {
            String entry = st.nextToken().trim();
            if (entry.startsWith("filename")) {
                filename = entry.substring(entry.indexOf("=") + 2, entry.length() - 1);
                break;
            }
        }
        return new UploadMetadata(part.getBody(new GenericType<File>() {}), filename);
    }

    /**
     * A private class that stores data related to a file upload request.
     */
    private class UploadMetadata {
        private File data;
        private String uploadedFilename;

        public UploadMetadata(File data, String uploadedFilename) {
            this.data = data;
            this.uploadedFilename = uploadedFilename;
        }

        public UploadMetadata() {
            this.data = null;
            this.uploadedFilename = "";
        }

        public File getData() {
            return data;
        }

        public String getUploadedFilename() {
            return uploadedFilename;
        }

    }

    /**
     * Retrieves an Owner instance for the owner with the specified key/account. If a matching owner
     * could not be found, this method throws an exception.
     *
     * @param key
     *  The key for the owner to retrieve
     *
     * @throws NotFoundException
     *  if an owner could not be found for the specified key.
     *
     * @return
     *  the Owner instance for the owner with the specified key.
     *
     * @httpcode 200
     * @httpcode 404
     */
    protected Owner getOwnerByKey(String key) {
        Owner owner = this.ownerCurator.getByKey(key);
        if (owner == null) {
            throw new NotFoundException(i18n.tr("Owner with key \"{0}\" was not found.", key));
        }

        return owner;
    }

    /**
     * Retrieves a Product instance for the product with the specified id. If no matching product
     * could be found, this method throws an exception.
     *
     * @param owner
     *  The organization
     *
     * @param productId
     *  The ID of the product to retrieve
     *
     * @throws NotFoundException
     *  if no matching product could be found with the specified id
     *
     * @return
     *  the Product instance for the product with the specified id
     */
    protected Product fetchProduct(Owner owner, String productId) {
        Product product = this.ownerProductCurator.getProductById(owner, productId);
        if (product == null) {
            throw new NotFoundException(i18n.tr("Product with ID \"{0}\" could not be found.", productId));
        }

        return product;
    }

    @Override
    public CandlepinQuery<ProductDTO> getProductsByOwner(@Verify(Owner.class) String ownerKey,
        List<String> productIds) {
        Owner owner = this.getOwnerByKey(ownerKey);
        CandlepinQuery<Product> query = productIds != null && !productIds.isEmpty() ?
            this.ownerProductCurator.getProductsByIds(owner, productIds) :
            this.ownerProductCurator.getProductsByOwner(owner);

        return this.translator.translateQuery(query, ProductDTO.class);
    }

    @Override
    public ProductDTO getProductByOwner(@Verify(Owner.class) String ownerKey, String productId) {
        Owner owner = this.getOwnerByKey(ownerKey);
        Product product = this.fetchProduct(owner, productId);

        return this.translator.translate(product, ProductDTO.class);
    }

    @Override
    @Transactional
    public ProductCertificateDTO getProductCertificateByOwner(String ownerKey, String productId) {
        if (!productId.matches("\\d+")) {
            throw new BadRequestException(i18n.tr("Only numeric product IDs are allowed."));
        }

        Owner owner = this.getOwnerByKey(ownerKey);
        Product product = this.fetchProduct(owner, productId);

        ProductCertificate productCertificate = this.productCertCurator.getCertForProduct(product);
        return this.translator.translate(productCertificate, ProductCertificateDTO.class);
    }

    @Override
    @Transactional
    public ProductDTO createProductByOwner(String ownerKey, ProductDTO dto) {
        this.validator.validateConstraints(dto);
        this.validator.validateCollectionElementsNotNull(
            dto::getBranding, dto::getDependentProductIds, dto::getProductContent);

        Owner owner = this.getOwnerByKey(ownerKey);
        Product entity = productManager.createProduct(dto, owner);

        return this.translator.translate(entity, ProductDTO.class);
    }

    @Override
    @Transactional
    public ProductDTO updateProductByOwner(String ownerKey, String productId, ProductDTO update) {
        if (StringUtils.isEmpty(update.getId())) {
            update.setId(productId);
        }
        else if (!StringUtils.equals(update.getId(), productId)) {
            throw new BadRequestException(
                i18n.tr("Contradictory ids in update request: {0}, {1}", productId, update.getId())
            );
        }

        this.validator.validateConstraints(update);
        this.validator.validateCollectionElementsNotNull(
            update::getBranding, update::getDependentProductIds, update::getProductContent);

        Owner owner = this.getOwnerByKey(ownerKey);

        // Get the matching owner_product & lock it while we are doing the update for this org
        // This is done in order to prevent collisions in updates on different parts of the product
        OwnerProduct ownerProduct = ownerProductCurator.getOwnerProductByProductId(owner, productId);
        ownerProductCurator.lock(ownerProduct);
        ownerProductCurator.refresh(ownerProduct);

        Product existing = ownerProduct.getProduct();

        if (existing.isLocked()) {
            throw new ForbiddenException(i18n.tr("product \"{0}\" is locked", existing.getId()));
        }

        Product updated = this.productManager.updateProduct(update, owner, true);

        return this.translator.translate(updated, ProductDTO.class);
    }

    @Override
    @Transactional
    public ProductDTO addBatchContent(String ownerKey, String productId, Map<String, Boolean> contentMap) {
        Owner owner = this.getOwnerByKey(ownerKey);

        // Get the matching owner_product & lock it while we are doing the update for this org
        // This is done in order to prevent collisions in updates on different parts of the product
        OwnerProduct ownerProduct = ownerProductCurator.getOwnerProductByProductId(owner, productId);
        ownerProductCurator.lock(ownerProduct);
        ownerProductCurator.refresh(ownerProduct);

        Product product = ownerProduct.getProduct();
        Collection<ProductContent> productContent = new LinkedList<>();

        if (product.isLocked()) {
            throw new ForbiddenException(i18n.tr("product \"{0}\" is locked", product.getId()));
        }

        ProductDTO pdto = this.translator.translate(product, ProductDTO.class);

        // Impl note:
        // This is a wholely inefficient way of doing this. When we return to using ID-based linking
        // and we're not linking the universe with our model, we can just attach the IDs directly
        // without needing all this DTO conversion back and forth.
        // Alternatively, we can shut off Hibernate's auto-commit junk and get in the habit of
        // calling commit methods as necessary so we don't have to work with DTOs internally.

        boolean changed = false;
        for (Map.Entry<String, Boolean> entry : contentMap.entrySet()) {
            Content content = this.fetchContent(owner, entry.getKey());
            boolean enabled = entry.getValue() != null ?
                entry.getValue() :
                ProductContent.DEFAULT_ENABLED_STATE;

            ContentDTO cdto = this.translator.translate(content, ContentDTO.class);

            changed |= addContent(pdto, cdto, enabled);
        }

        if (changed) {
            product = this.productManager.updateProduct(pdto, owner, true);
        }

        return this.translator.translate(product, ProductDTO.class);
    }

    private boolean addContent(ProductDTO product, ContentDTO dto, boolean enabled) {
        if (product == null) {
            throw new IllegalArgumentException("Cannot add content to null product");
        }
        if (dto == null || dto.getId() == null) {
            throw new IllegalArgumentException("dto references incomplete content");
        }

        ProductContentDTO content = new ProductContentDTO();
        content.setContent(dto);
        content.setEnabled(enabled);

        Set<ProductContentDTO> productContent;
        if (product.getProductContent() == null) {
            productContent = new HashSet<>();
        }
        else {
            productContent = new HashSet<>(product.getProductContent());
        }

        boolean changed = productContent.stream()
            .filter(contentDTO -> contentDTO.getContent().getId().equals(content.getContent().getId()))
            .noneMatch(contentDTO -> contentDTO.equals(content));

        if (changed) {
            productContent.add(content);
            product.setProductContent(productContent);
        }

        return changed;
    }

    @Override
    @Transactional
    public ProductDTO addContent(String ownerKey, String productId, String contentId, Boolean enabled) {
        // Package the params up and pass it off to our batch method
        Map<String, Boolean> contentMap = Collections.singletonMap(contentId, enabled);
        return this.addBatchContent(ownerKey, productId, contentMap);
    }

    @Override
    @Transactional
    public ProductDTO removeBatchContent(String ownerKey, String productId, List<String> contentIds) {
        Owner owner = this.getOwnerByKey(ownerKey);

        // Get the matching owner_product & lock it while we are doing the update for this org
        // This is done in order to prevent collisions in updates on different parts of the product
        OwnerProduct ownerProduct = ownerProductCurator.getOwnerProductByProductId(owner, productId);
        ownerProductCurator.lock(ownerProduct);
        ownerProductCurator.refresh(ownerProduct);
        Product product = ownerProduct.getProduct();

        if (product.isLocked()) {
            throw new ForbiddenException(i18n.tr("product \"{0}\" is locked", product.getId()));
        }

        ProductDTO pdto = this.translator.translate(product, ProductDTO.class);

        // Impl note:
        // This is a wholely inefficient way of doing this. When we return to using ID-based linking
        // and we're not linking the universe with our model, we can just attach the IDs directly
        // without needing all this DTO conversion back and forth.
        // Alternatively, we can shut off Hibernate's auto-commit junk and get in the habit of
        // calling commit methods as necessary so we don't have to work with DTOs internally.

        boolean changed = removeContent(pdto, new HashSet<>(contentIds));

        if (changed) {
            product = this.productManager.updateProduct(pdto, owner, true);
        }

        return this.translator.translate(product, ProductDTO.class);
    }

    public boolean removeContent(ProductDTO product, Set<String> contentIds) {
        if (contentIds == null) {
            throw new IllegalArgumentException("contentId is null");
        }
        if (contentIds.isEmpty()) {
            return false;
        }
        int originalSize = product.getProductContent().size();
        Set<ProductContentDTO> updatedContents = product.getProductContent().stream()
            .filter(content -> !contentIds.contains(content.getContent().getId()))
            .collect(Collectors.toSet());

        product.setProductContent(updatedContents);

        return originalSize != updatedContents.size();
    }

    @Override
    @Transactional
    public ProductDTO removeContent(String ownerKey, String productId, String contentId) {
        // Package up the params and pass it to our bulk operation
        return this.removeBatchContent(ownerKey, productId, Collections.singletonList(contentId));
    }

    @Override
    @Transactional
    public void deleteProductByOwner(String ownerKey, String productId) {
        Owner owner = this.getOwnerByKey(ownerKey);
        Product product = this.fetchProduct(owner, productId);

        if (product.isLocked()) {
            throw new ForbiddenException(i18n.tr("product \"{0}\" is locked", product.getId()));
        }

        if (this.productCurator.productHasSubscriptions(owner, product)) {
            throw new BadRequestException(
                i18n.tr("Product with ID \"{0}\" cannot be deleted while subscriptions exist.", productId));
        }

        this.productManager.removeProduct(owner, product);
    }

    @Override
    @Transactional
    public AsyncJobStatusDTO refreshPoolsForProduct(String ownerKey, String productId, Boolean lazyRegen) {

        if (config.getBoolean(ConfigProperties.STANDALONE)) {
            log.warn("Ignoring refresh pools request due to standalone config.");
            return null;
        }

        Owner owner = this.getOwnerByKey(ownerKey);
        Product product = this.fetchProduct(owner, productId);
        JobConfig config = RefreshPoolsForProductJob.createJobConfig()
            .setProduct(product)
            .setLazy(lazyRegen);

        try {
            AsyncJobStatus status = jobManager.queueJob(config);
            return this.translator.translate(status, AsyncJobStatusDTO.class);
        }
        catch (JobException e) {
            String errmsg = this.i18n.tr("An unexpected exception occurred while scheduling job \"{0}\"",
                config.getJobKey());
            log.error(errmsg, e);
            throw new IseException(errmsg, e);
        }
    }

    /**
     * Retrieves the content entity with the given content ID for the specified owner. If a
     * matching entity could not be found, this method throws a NotFoundException.
     *
     * @param owner
     *  The owner in which to search for the content
     *
     * @param contentId
     *  The Red Hat ID of the content to retrieve
     *
     * @throws NotFoundException
     *  If a content with the specified Red Hat ID could not be found
     *
     * @return
     *  the content entity with the given owner and content ID
     */
    protected Content fetchContent(Owner owner, String contentId) {
        Content content = this.ownerContentCurator.getContentById(owner, contentId);

        if (content == null) {
            throw new NotFoundException(
                    i18n.tr("Content with ID \"{0}\" could not be found.", contentId)
            );
        }

        return content;
    }

    @Override
    public CandlepinQuery<ContentDTO> listOwnerContent(
        @Verify(Owner.class) String ownerKey) {

        final Owner owner = this.getOwnerByKey(ownerKey);

        CandlepinQuery<Content> query = this.ownerContentCurator.getContentByOwner(owner);
        return this.translator.translateQuery(query, ContentDTO.class);
    }

    @Override
    public ContentDTO getOwnerContent(
        @Verify(Owner.class) String ownerKey, String contentId) {

        Owner owner = this.getOwnerByKey(ownerKey);
        Content content = this.fetchContent(owner, contentId);

        return this.translator.translate(content, ContentDTO.class);
    }

    /**
     * Creates or merges the given Content object.
     *
     * @param owner
     *  The owner for which to create the new content
     *
     * @param content
     *  The content to create or merge
     *
     * @return
     *  the newly created and/or merged Content object.
     */

    private Content createContentImpl(Owner owner, ContentDTO content) {
        // TODO: check if arches have changed ??

        Content entity = null;

        if (content.getId() == null || content.getId().trim().length() == 0) {
            content.setId(this.idGenerator.generateId());

            entity = this.contentManager.createContent(content, owner);
        }
        else {
            Content existing = this.ownerContentCurator.getContentById(owner, content.getId());

            if (existing != null) {
                if (existing.isLocked()) {
                    throw new ForbiddenException(i18n.tr("content \"{0}\" is locked", existing.getId()));
                }

                entity = this.contentManager.updateContent(content, owner, true);
            }
            else {
                entity = this.contentManager.createContent(content, owner);
            }
        }

        return entity;
    }

    @Override
    public ContentDTO createContent(String ownerKey, ContentDTO content) {

        this.validator.validateCollectionElementsNotNull(content::getModifiedProductIds);

        Owner owner = this.getOwnerByKey(ownerKey);
        Content entity = this.createContentImpl(owner, content);

        contentAccessManager.refreshOwnerForContentAccess(owner);

        return this.translator.translate(entity, ContentDTO.class);
    }

    @Override
    @Transactional
    public Collection<ContentDTO> createBatchContent(String ownerKey, List<ContentDTO> contents) {

        for (ContentDTO content : contents) {
            this.validator.validateCollectionElementsNotNull(content::getModifiedProductIds);
        }

        Collection<ContentDTO> result = new LinkedList<>();
        Owner owner = this.getOwnerByKey(ownerKey);

        for (ContentDTO content : contents) {
            Content entity = this.createContentImpl(owner, content);
            result.add(this.translator.translate(entity, ContentDTO.class));
        }

        contentAccessManager.refreshOwnerForContentAccess(owner);
        return result;
    }

    @Override
    public ContentDTO updateContent(String ownerKey, String contentId, ContentDTO content) {

        this.validator.validateCollectionElementsNotNull(content::getModifiedProductIds);

        Owner owner = this.getOwnerByKey(ownerKey);
        Content existing  = this.fetchContent(owner, contentId);

        if (existing.isLocked()) {
            throw new ForbiddenException(i18n.tr("content \"{0}\" is locked", existing.getId()));
        }

        existing = this.contentManager.updateContent(content, owner, true);
        contentAccessManager.refreshOwnerForContentAccess(owner);

        return this.translator.translate(existing, ContentDTO.class);
    }

    @Override
    public void remove(String ownerKey, String contentId) {
        Owner owner = this.getOwnerByKey(ownerKey);
        Content content = this.fetchContent(owner, contentId);

        if (content.isLocked()) {
            throw new ForbiddenException(i18n.tr("content \"{0}\" is locked", content.getId()));
        }

        this.contentManager.removeContent(owner, content, true);
        contentAccessManager.refreshOwnerForContentAccess(owner);
    }

}
