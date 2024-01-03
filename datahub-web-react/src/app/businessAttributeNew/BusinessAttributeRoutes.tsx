import React, { useState } from 'react';
import styled from 'styled-components/macro';
import { Switch, Route } from 'react-router-dom';
import { PageRoutes } from '../../conf/Global';
import { GlossaryEntityContext } from '../entity/shared/GlossaryEntityContext';
import { GenericEntityProperties } from '../entity/shared/types';
import BusinessAttributePage from './BusinessAttributePage';
import { EntityPage } from '../entity/EntityPage';
import { useEntityRegistry } from '../useEntityRegistry';
import { EntityType } from '../../types.generated';

const ContentWrapper = styled.div`
    display: flex;
    flex: 1;
    overflow: hidden;
`;

export default function BusinessAttributeRoutes() {
    const entityRegistry = useEntityRegistry();
    const [entityData, setEntityData] = useState<GenericEntityProperties | null>(null);
    const [urnsToUpdate, setUrnsToUpdate] = useState<string[]>([]);

    return (
        <GlossaryEntityContext.Provider
            value={{ isInGlossaryContext: true, entityData, setEntityData, urnsToUpdate, setUrnsToUpdate }}
        >
            <ContentWrapper>
                <Switch>
                    <Route
                        key={entityRegistry.getPathName(EntityType.BusinessAttribute)}
                        path={`/${entityRegistry.getPathName(EntityType.BusinessAttribute)}/:urn`}
                        render={() => <EntityPage entityType={EntityType.BusinessAttribute} />}
                    />
                    <Route path={`${PageRoutes.BUSINESS_ATTRIBUTE}`} render={() => <BusinessAttributePage />} />
                </Switch>
            </ContentWrapper>
        </GlossaryEntityContext.Provider>
    );
}
