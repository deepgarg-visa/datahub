import * as React from 'react';
import { DatabaseFilled, DatabaseOutlined } from '@ant-design/icons';
import { BusinessAttribute, EntityType, SearchResult } from '../../../types.generated';
import { Entity, EntityCapabilityType, IconStyleType, PreviewType } from '../Entity';
import { getDataForEntityType } from '../shared/containers/profile/utils';
import DomainIcon from '../../domain/DomainIcon';
import DefaultPreviewCard from '../../preview/DefaultPreviewCard';

/**
 *  Definition of datahub Business Attribute Entity
 */
/* eslint-disable @typescript-eslint/no-unused-vars */
export class BusinessAttributeEntity implements Entity<BusinessAttribute> {
    type: EntityType = EntityType.BusinessAttribute;

    icon = (fontSize: number, styleType: IconStyleType, color?: string) => {
        if (styleType === IconStyleType.TAB_VIEW) {
            return <DatabaseOutlined style={{ fontSize, color }} />;
        }

        if (styleType === IconStyleType.HIGHLIGHT) {
            return <DatabaseFilled style={{ fontSize, color: color || '#B37FEB' }} />;
        }

        if (styleType === IconStyleType.SVG) {
            // TODO: Update the returned path value to the correct svg icon path
            return (
                <path d="M832 64H192c-17.7 0-32 14.3-32 32v832c0 17.7 14.3 32 32 32h640c17.7 0 32-14.3 32-32V96c0-17.7-14.3-32-32-32zm-600 72h560v208H232V136zm560 480H232V408h560v208zm0 272H232V680h560v208zM304 240a40 40 0 1080 0 40 40 0 10-80 0zm0 272a40 40 0 1080 0 40 40 0 10-80 0zm0 272a40 40 0 1080 0 40 40 0 10-80 0z" />
            );
        }

        return (
            <DatabaseOutlined
                style={{
                    fontSize,
                    color: color || '#BFBFBF',
                }}
            />
        );
    };

    displayName = (data: BusinessAttribute) => {
        console.log('displayName:::', data?.businessAttributeInfo);
        return data?.businessAttributeInfo?.name || data?.urn;
    };

    getPathName = () => 'businessAttribute';

    getEntityName = () => 'Business Attribute';

    getCollectionName = () => 'Business Attributes';

    isBrowseEnabled = () => true;

    isLineageEnabled = () => false;

    isSearchEnabled = () => true;

    getOverridePropertiesFromEntity = (data: BusinessAttribute) => {
        return {
            name: data.businessAttributeInfo?.name,
        };
    };

    getGenericEntityProperties = (data: BusinessAttribute) => {
        return getDataForEntityType({
            data,
            entityType: this.type,
            getOverrideProperties: this.getOverridePropertiesFromEntity,
        });
    };

    renderPreview = (_: PreviewType, data: BusinessAttribute) => {
        // TODO: Create a preview tsx file inside businessAttribute entity and attach it here
        return (
            <DefaultPreviewCard
                url="/somePath"
                name={this.displayName(data)}
                urn={data.urn}
                description={data.businessAttributeInfo?.description || ''}
                type="Business Attribute"
                typeIcon={
                    <DomainIcon
                        style={{
                            fontSize: 14,
                            color: 'Red',
                        }}
                    />
                }
                owners={data.ownership?.owners}
                logoComponent={this.icon(12, IconStyleType.ACCENT)}
                insights={null}
                parentEntities={null}
                snippet={null}
            />
        );
    };

    renderProfile = (urn: string) => {
        // TODO: Use EntityProfile inside shared folder
        return <></>;
    };

    renderSearch = (result: SearchResult) => {
        // TODO: add preview for search result like renderPreview
        return (
            <DatabaseOutlined
                style={{
                    fontSize: 12,
                    color: 'Brown',
                }}
            />
        );
    };

    supportedCapabilities = () => {
        return new Set([
            EntityCapabilityType.OWNERS,
            EntityCapabilityType.TAGS,
            EntityCapabilityType.GLOSSARY_TERMS,
            EntityCapabilityType.BUSINESS_ATTRIBUTES,
        ]);
    };
}
